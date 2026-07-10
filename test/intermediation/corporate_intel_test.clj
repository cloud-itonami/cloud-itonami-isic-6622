(ns intermediation.corporate-intel-test
  "Proves the value `intermediation.corporate-intel` actually adds: a
  broker that is clean on every LOCAL field (no `:conflict-hit?`, has a
  `:disclosure-doc` on file) but has an undisclosed professional-
  capacity relationship with THIS placement's customer, sourced in
  cloud-itonami-isic-8291's own demo relationship-graph data, no longer
  silently clears -- something 6622's local-only checks alone would have
  missed entirely (party-3 \"山田 一郎(デモ)\" / placement-4's customer
  party-5 \"Northwind Capital Holdings Ltd (demo)\", named to match
  8291's `dossier.store/demo-data` official `of-1` and sanctions-flagged
  company `co-200` exactly -- of-1 sits on co-200's board via a real,
  sourced `:directorship` relationship edge, and co-200 is itself
  sanctions-flagged).

  Note: `:conflict/screen` NEVER auto-commits at any phase in this repo
  (only `:placement/intake` does, see `intermediation.phase`) -- every
  scenario below that reaches `:commit` does so via an explicit approve,
  same as every other `:conflict/screen` test in
  `governor_contract_test.clj`. Because party-5 resolves to a
  SANCTIONS-FLAGGED company, the REAL (unstubbed) 8291 call itself
  high-stakes-escalates for 8291's OWN human reviewer first (never an
  immediate synchronous 'yes it's a hit') -- so end-to-end the real
  scenario reads as 'no longer silently clears' (verdict :incomplete,
  soft escalate), not 'now hard-holds'. The HARD, un-overridable
  immediate-hold path (`:verdict :hit`) is only reachable once a human
  has confirmed the hit on 8291's side (or, deterministically for this
  test suite, via a stub standing in for that confirmed answer) -- see
  `corporate-intel-stubbed-related-hard-holds-deterministically`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [intermediation.store :as store]
            [intermediation.operation :as op]
            [intermediation.brokerllm :as brokerllm]
            [intermediation.corporate-intel :as ci]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-check ci/check-relationship})})]))

(deftest sanity-without-placement-id-is-byte-for-byte-unchanged
  (testing "existing callers that omit :placement-id (every caller before this integration) keep the
            EXACT prior behavior, even with the real corporate-intel check wired into the advisor"
    (let [[db actor] (wired-actor)
          res (exec-op actor "sanity" {:op :conflict/screen :subject "party-2"} operator)]
      (is (= :interrupted (:status res)) "conflict/screen always escalates for approval, clean or not")
      (approve! actor "sanity")
      (is (= :clear (:verdict (store/conflict-of db "party-2"))))
      (is (not (some #{:corporate-intelligence} (:cites (get-in res [:state :proposal]))))
          "no :placement-id -> corporate-intelligence is never even cited"))))

(deftest corporate-intel-catches-the-relationship-local-checks-miss
  (testing "without the integration wired in, party-3 passes every local check and clears -- this is
            the gap being closed"
    (let [db (store/seed-db)
          actor (op/build db)                          ; NO corporate-intel wired in
          res (exec-op actor "gap" {:op :conflict/screen :subject "party-3" :placement-id "placement-4"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "gap")
      (is (= :clear (:verdict (store/conflict-of db "party-3")))
          "without the integration, party-3 screens :clear even WITH :placement-id supplied,
           because the default no-op corporate-intel-check never changes the verdict"))))

(deftest corporate-intel-real-relationship-no-longer-silently-clears
  (testing "with the REAL (unmocked) 8291 actor wired in, party-3 no longer silently clears -- 8291
            itself escalates the real directorship-edge-to-a-sanctions-flagged-company hit for ITS
            OWN human review first, so 6622 never peeks behind that gate: this reads as :incomplete +
            low confidence here, and 6622's OWN op ALSO escalates (soft, confidence 0.5)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :conflict/screen :subject "party-3" :placement-id "placement-4"} operator)]
      (is (= :interrupted (:status res))
          "8291's own escalation surfaces here as low confidence (0.5), not as an immediate hard hold")
      (is (= :low-confidence (-> res :state :audit last :reason)))
      (approve! actor "t1")
      (is (not= :clear (:verdict (store/conflict-of db "party-3")))
          "critically: it never becomes :clear, unlike the unwired gap case above")
      (is (= :incomplete (:verdict (store/conflict-of db "party-3")))))))

(deftest corporate-intel-stubbed-related-hard-holds-deterministically
  (testing "screen-conflict's :related? branch itself is a HARD, un-overridable hold -- proven directly
            with a stub standing in for '8291's human reviewer has confirmed the hit' (a real 8291 hit
            always escalates for 8291's own human first, so this branch is only reachable end-to-end
            after that confirmation; unit-testing it here keeps the assertion deterministic)"
    (let [db (store/seed-db)
          definitive-hit (fn [_broker-name _customer-name] {:found? true :related? true :kind :directorship})
          actor (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-check definitive-hit})})
          res (exec-op actor "t2" {:op :conflict/screen :subject "party-3" :placement-id "placement-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "party-3")) "no conflict clearance written"))))

(deftest corporate-intel-held-screen-degrades-to-incomplete-not-clear
  (testing "if 6622's own tenant contract with 8291 is missing/misconfigured, 8291 itself holds the
            query -- 6622 must treat that as inconclusive (escalate), never as clear"
    (let [db (store/seed-db)
          broken-check (fn [_broker-name _customer-name] {:held? true :reason [:licensed-disclosure]})
          actor (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-check broken-check})})
          res (exec-op actor "t3" {:op :conflict/screen :subject "party-3" :placement-id "placement-4"} operator)]
      (is (= :interrupted (:status res)) "low confidence (:incomplete) -> escalate, same as a missing disclosure-doc")
      (is (= :low-confidence (-> res :state :audit last :reason)))
      (approve! actor "t3")
      (is (not= :clear (:verdict (store/conflict-of db "party-3"))) "critically: it never becomes :clear")
      (is (= :incomplete (:verdict (store/conflict-of db "party-3")))))))

(deftest corporate-intel-clean-customer-with-no-8291-data-still-clears
  (testing "a placement whose customer has NO match in 8291's demo data (existing placement-1,
            customer party-1 named \"customer\") still clears normally once the local disclosure-doc
            exists -- additive, not stricter-by-default (a confident not-found is not treated as a hit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t4" {:op :conflict/screen :subject "party-2" :placement-id "placement-1"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t4")
      (is (= :clear (:verdict (store/conflict-of db "party-2"))))
      (is (some #{:corporate-intelligence} (:cites (get-in res [:state :proposal]))))))
  (testing "sanity: without :placement-id at all, the SAME party-2 also clears (already covered above,
            repeated here to make the additive contrast between the two calls explicit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t5" {:op :conflict/screen :subject "party-2"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t5")
      (is (= :clear (:verdict (store/conflict-of db "party-2")))))))

(deftest corporate-intel-local-conflict-hit-short-circuits-before-8291-is-consulted
  (testing "a local :conflict-hit? decides the verdict first -- 8291 is never even queried, even
            with :placement-id supplied"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t6" {:op :conflict/screen :subject "party-4" :placement-id "placement-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis))))))
