(ns intermediation.store
  "SSoT for the insurance-intermediation actor, behind a `Store` protocol
  so the backend is a swap, not a rewrite -- the same seam
  `cloud-itonami-isic-6511`'s `underwriting.store` / `cloud-itonami-isic-
  6512`'s `casualty.store` / `cloud-itonami-isic-6621`'s
  `adjustment.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/intermediation/store_contract_test.clj), which is the whole
  point: the actor, the Insurance Intermediation Governor and the audit
  ledger never know which SSoT they run on.

  The ledger stays append-only on every backend: 'which placement was
  bound with which insurer on the customer's behalf, which broker was
  screened for conflict of interest, which commission was booked,
  approved by whom' is always a query over an immutable log -- the
  audit trail a customer trusting a broker with a placement needs, and
  the evidence an operator needs if a placement or a commission is later
  disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [intermediation.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (placement [s id])
  (all-placements [s])
  (party [s id])
  (conflict-of [s party-id] "committed conflict-of-interest screening verdict for a party, or nil")
  (assessment-of [s placement-id] "committed jurisdiction licensing/commission-cap assessment, or nil")
  (ledger [s])
  (binding-history [s] "the append-only placement-binding history (intermediation.registry drafts)")
  (commission-history [s] "the append-only commission-booking history (intermediation.registry drafts)")
  (next-sequence [s jurisdiction] "next placement-number sequence for a jurisdiction")
  (commission-sequence [s jurisdiction] "next commission-booking-number sequence for a jurisdiction")
  (commission-already-booked? [s placement-number] "has a commission already been booked for this placement?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-placements [s placements] "replace/seed the placement directory (map id->placement)")
  (with-parties [s parties] "replace/seed the party directory (map id->party)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained placement/party set so the actor + tests run
  offline."
  []
  {:placements
   {"placement-1" {:id "placement-1" :customer "party-1" :broker "party-2"
                   :needs "auto insurance for 2019 Honda Civic"
                   :quotes [{:insurer "Insurer A" :premium 120000 :commission-rate 0.10}
                            {:insurer "Insurer B" :premium 100000 :commission-rate 0.15}]
                   :selected-insurer "Insurer B" :selected-premium 100000
                   :selected-commission-rate 0.15
                   :jurisdiction "JPN" :status :intake}
    "placement-2" {:id "placement-2" :customer "party-1" :broker "party-4"
                   :needs "fire insurance for 123 Oak St"
                   :quotes [{:insurer "Insurer C" :premium 50000 :commission-rate 0.30}]
                   :selected-insurer "Insurer C" :selected-premium 50000
                   :selected-commission-rate 0.30
                   :jurisdiction "ATL" :status :intake}
    "placement-3" {:id "placement-3" :customer "party-1" :broker "party-2"
                   :needs "home insurance for 456 Elm St"
                   :quotes [{:insurer "Insurer D" :premium 80000 :commission-rate 0.25}
                            {:insurer "Insurer E" :premium 90000 :commission-rate 0.22}]
                   :selected-insurer "Insurer D" :selected-premium 80000
                   :selected-commission-rate 0.25
                   :jurisdiction "JPN" :status :intake}}
   :parties
   {"party-1" {:id "party-1" :name "customer" :role :customer
               :conflict-hit? false :disclosure-doc nil}
    "party-2" {:id "party-2" :name "田中 一郎" :role :broker
               :conflict-hit? false :disclosure-doc "form-jp-****1234"}
    "party-4" {:id "party-4" :name "J. Doe" :role :broker
               :conflict-hit? true :disclosure-doc nil}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- bind!
  "Backend-agnostic `:placement/mark-bound` -- looks up the placement via
  the protocol, drafts the placement-binding record, and returns
  {:result .. :placement-patch ..} for the caller to persist."
  [s placement-id]
  (let [p (placement s placement-id)
        seq-n (next-sequence s (:jurisdiction p))
        result (registry/register-binding
                (:customer p) (:selected-insurer p) (:selected-premium p) (:jurisdiction p) seq-n)]
    {:result result
     :placement-patch {:status :bound
                       :placement-number (get result "placement_number")}}))

(defn- book-commission!
  "Backend-agnostic `:commission/mark-booked` -- looks up the placement
  via the protocol, drafts the commission-booking record, and returns
  {:result .. :placement-patch ..} for the caller to persist."
  [s placement-id]
  (let [p (placement s placement-id)
        commission-amount (* (double (:selected-premium p)) (double (:selected-commission-rate p)))
        seq-n (commission-sequence s (:jurisdiction p))
        result (registry/register-commission-booking
                (:placement-number p) (:selected-commission-rate p) commission-amount (:jurisdiction p) seq-n)]
    {:result result
     :placement-patch {:status :commission-booked
                       :booking-number (get result "booking_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (placement [_ id] (get-in @a [:placements id]))
  (all-placements [_] (sort-by :id (vals (:placements @a))))
  (party [_ id] (get-in @a [:parties id]))
  (conflict-of [_ id] (get-in @a [:conflicts id]))
  (assessment-of [_ placement-id] (get-in @a [:assessments placement-id]))
  (ledger [_] (:ledger @a))
  (binding-history [_] (:bindings @a))
  (commission-history [_] (:commissions @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (commission-sequence [_ jurisdiction] (get-in @a [:commission-sequences jurisdiction] 0))
  (commission-already-booked? [_ placement-number]
    (boolean (some #(= placement-number (get % "placement_number")) (:commissions @a))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :placement/upsert
      (swap! a update-in [:placements (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :conflict/set
      (swap! a assoc-in [:conflicts (first path)] payload)

      :placement/mark-bound
      (let [placement-id (first path)
            {:keys [result placement-patch]} (bind! s placement-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:placements placement-id]))] (fnil inc 0))
                       (update-in [:placements placement-id] merge placement-patch)
                       (update :bindings registry/append result))))
        result)

      :commission/mark-booked
      (let [placement-id (first path)
            {:keys [result placement-patch]} (book-commission! s placement-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:commission-sequences (:jurisdiction (get-in state [:placements placement-id]))] (fnil inc 0))
                       (update-in [:placements placement-id] merge placement-patch)
                       (update :commissions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-placements [s placements] (when (seq placements) (swap! a assoc :placements placements)) s)
  (with-parties [s parties] (when (seq parties) (swap! a assoc :parties parties)) s))

(defn seed-db
  "A MemStore seeded with the demo placement/party set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :conflicts {} :ledger [] :sequences {}
                           :bindings [] :commission-sequences {} :commissions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (quotes, assessment/conflict payloads, ledger
  facts, binding/commission records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention `underwriting.store`/`casualty.store`/`adjustment.store`
  use."
  {:placement/id                {:db/unique :db.unique/identity}
   :party/id                    {:db/unique :db.unique/identity}
   :conflict/party-id           {:db/unique :db.unique/identity}
   :assessment/placement-id     {:db/unique :db.unique/identity}
   :ledger/seq                  {:db/unique :db.unique/identity}
   :binding/seq                 {:db/unique :db.unique/identity}
   :commission/seq              {:db/unique :db.unique/identity}
   :sequence/jurisdiction       {:db/unique :db.unique/identity}
   :commission-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- placement->tx [{:keys [id customer broker needs quotes selected-insurer
                             selected-premium selected-commission-rate
                             jurisdiction status placement-number booking-number]}]
  (cond-> {:placement/id id}
    customer                   (assoc :placement/customer customer)
    broker                     (assoc :placement/broker broker)
    needs                      (assoc :placement/needs needs)
    quotes                     (assoc :placement/quotes (enc quotes))
    selected-insurer           (assoc :placement/selected-insurer selected-insurer)
    selected-premium           (assoc :placement/selected-premium selected-premium)
    selected-commission-rate   (assoc :placement/selected-commission-rate selected-commission-rate)
    jurisdiction               (assoc :placement/jurisdiction jurisdiction)
    status                     (assoc :placement/status status)
    placement-number           (assoc :placement/placement-number placement-number)
    booking-number             (assoc :placement/booking-number booking-number)))

(def ^:private placement-pull
  [:placement/id :placement/customer :placement/broker :placement/needs :placement/quotes
   :placement/selected-insurer :placement/selected-premium :placement/selected-commission-rate
   :placement/jurisdiction :placement/status :placement/placement-number :placement/booking-number])

(defn- pull->placement [m]
  (when (:placement/id m)
    {:id (:placement/id m) :customer (:placement/customer m) :broker (:placement/broker m)
     :needs (:placement/needs m) :quotes (or (dec* (:placement/quotes m)) [])
     :selected-insurer (:placement/selected-insurer m) :selected-premium (:placement/selected-premium m)
     :selected-commission-rate (:placement/selected-commission-rate m)
     :jurisdiction (:placement/jurisdiction m) :status (:placement/status m)
     :placement-number (:placement/placement-number m) :booking-number (:placement/booking-number m)}))

(defn- party->tx [{:keys [id name role conflict-hit? disclosure-doc]}]
  (cond-> {:party/id id}
    name (assoc :party/name name)
    role (assoc :party/role role)
    (some? conflict-hit?) (assoc :party/conflict-hit? conflict-hit?)
    disclosure-doc (assoc :party/disclosure-doc disclosure-doc)))

(defn- pull->party [m]
  (when (:party/id m)
    {:id (:party/id m) :name (:party/name m) :role (:party/role m)
     :conflict-hit? (boolean (:party/conflict-hit? m)) :disclosure-doc (:party/disclosure-doc m)}))

(defrecord DatomicStore [conn]
  Store
  (placement [_ id]
    (pull->placement (d/pull (d/db conn) placement-pull [:placement/id id])))
  (all-placements [_]
    (->> (d/q '[:find [?id ...] :where [?e :placement/id ?id]] (d/db conn))
         (map #(pull->placement (d/pull (d/db conn) placement-pull [:placement/id %])))
         (sort-by :id)))
  (party [_ id]
    (pull->party (d/pull (d/db conn)
                         [:party/id :party/name :party/role :party/conflict-hit? :party/disclosure-doc]
                         [:party/id id])))
  (conflict-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?k :conflict/party-id ?pid] [?k :conflict/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ placement-id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?a :assessment/placement-id ?pid] [?a :assessment/payload ?p]]
              (d/db conn) placement-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (binding-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :binding/seq ?s] [?e :binding/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commission-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :commission/seq ?s] [?e :commission/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commission-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :commission-sequence/jurisdiction ?j] [?e :commission-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commission-already-booked? [s placement-number]
    (boolean (some #(= placement-number (get % "placement_number")) (commission-history s))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :placement/upsert
      (d/transact! conn [(placement->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/placement-id (first path) :assessment/payload (enc payload)}])

      :conflict/set
      (d/transact! conn [{:conflict/party-id (first path) :conflict/payload (enc payload)}])

      :placement/mark-bound
      (let [placement-id (first path)
            {:keys [result placement-patch]} (bind! s placement-id)
            jurisdiction (:jurisdiction (placement s placement-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(placement->tx (assoc placement-patch :id placement-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:binding/seq (count (binding-history s)) :binding/record (enc (get result "record"))}])
        result)

      :commission/mark-booked
      (let [placement-id (first path)
            {:keys [result placement-patch]} (book-commission! s placement-id)
            jurisdiction (:jurisdiction (placement s placement-id))
            next-n (inc (commission-sequence s jurisdiction))]
        (d/transact! conn
                     [(placement->tx (assoc placement-patch :id placement-id))
                      {:commission-sequence/jurisdiction jurisdiction :commission-sequence/next next-n}
                      {:commission/seq (count (commission-history s)) :commission/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-placements [s placements]
    (when (seq placements) (d/transact! conn (mapv placement->tx (vals placements)))) s)
  (with-parties [s parties]
    (when (seq parties) (d/transact! conn (mapv party->tx (vals parties)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:placements .. :parties ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [placements parties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-placements placements) (with-parties parties)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo placement/party set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
