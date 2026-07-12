(ns intermediation.governor-contract-test
  "The governor contract as executable tests -- the intermediation
  analog of `cloud-itonami-isic-6511`'s `underwriting.governor-contract-
  test` / `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`
  / `cloud-itonami-isic-6621`'s `adjustment.governor-contract-test`. The
  single invariant under test:

    Broker-LLM never binds a placement or books a commission the
    Insurance Intermediation Governor would reject, `:placement/bind`/
    `:commission/book` NEVER auto-commit at any phase, `:placement/
    intake` (no liability risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [intermediation.store :as store]
            [intermediation.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- bind-placement1!
  "Walks placement-1 through assess -> approve -> conflict-screen ->
  approve -> bind -> approve, leaving placement-1 :bound. Uses distinct
  thread-ids per call site by suffixing `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "placement-1"} operator)
  (approve! actor (str tid-prefix "-assess"))
  (exec-op actor (str tid-prefix "-conflict") {:op :conflict/screen :subject "party-2"} operator)
  (approve! actor (str tid-prefix "-conflict"))
  (exec-op actor (str tid-prefix "-bind") {:op :placement/bind :subject "placement-1"} operator)
  (approve! actor (str tid-prefix "-bind")))

(defn- bind-placement3!
  "Same as `bind-placement1!` but for placement-3 (rate 0.25, exceeds
  the JPN 0.20 cap)."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "placement-3"} operator)
  (approve! actor (str tid-prefix "-assess"))
  (exec-op actor (str tid-prefix "-conflict") {:op :conflict/screen :subject "party-2"} operator)
  (approve! actor (str tid-prefix "-conflict"))
  (exec-op actor (str tid-prefix "-bind") {:op :placement/bind :subject "placement-3"} operator)
  (approve! actor (str tid-prefix "-bind")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :placement/intake :subject "placement-1"
                   :patch {:id "placement-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/placement db "placement-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "placement-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "placement-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "placement-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "placement-1")) "no assessment written"))))

(deftest conflict-of-interest-is-held-and-unoverridable
  (testing "a conflict-of-interest hit on a party -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :conflict/screen :subject "party-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "party-4")) "no conflict clearance written"))))

(deftest bind-with-insufficient-quotes-is-held
  (testing "placement-2 has only ONE compared quote -> HOLD (insufficient-quotes), never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :placement/bind :subject "placement-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:insufficient-quotes} (-> (store/ledger db) first :basis)))
      (is (empty? (store/binding-history db))))))

(deftest placement-bind-always-escalates-then-human-decides
  (testing "a clean, fully-assessed binding still ALWAYS interrupts for human approval -- actuation/bind is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "placement-1"} operator)
          _ (approve! actor "t6a")
          _ (exec-op actor "t6b" {:op :conflict/screen :subject "party-2"} operator)
          _ (approve! actor "t6b")
          r1 (exec-op actor "t6" {:op :placement/bind :subject "placement-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, placement-binding record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :bound (:status (store/placement db "placement-1"))))
          (is (= 1 (count (store/binding-history db))) "one draft binding record")))))
  (testing "reject -> hold, nothing bound"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "placement-1"} operator)
          _ (approve! actor "t7a")
          _ (exec-op actor "t7" {:op :placement/bind :subject "placement-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/binding-history db)) "nothing bound on reject"))))

(deftest commission-book-against-unbound-placement-is-held
  (testing "a commission booked for a placement that was never bound -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :commission/book :subject "placement-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:placement-not-bound} (-> (store/ledger db) first :basis)))
      (is (empty? (store/commission-history db))))))

(deftest commission-book-exceeding-cap-is-held
  (testing "placement-3's commission rate (0.25) exceeds JPN's 0.20 cap -> HOLD"
    (let [[db actor] (fresh)
          _ (bind-placement3! actor "t9pre")
          res (exec-op actor "t9" {:op :commission/book :subject "placement-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:commission-rate-exceeds-cap} (-> (store/ledger db) last :basis)))
      (is (empty? (store/commission-history db))))))

(deftest commission-book-always-escalates-then-human-decides
  (testing "a clean, in-cap booking still ALWAYS interrupts for human approval -- actuation/book-commission is never auto"
    (let [[db actor] (fresh)
          _ (bind-placement1! actor "t10pre")
          r1 (exec-op actor "t10" {:op :commission/book :subject "placement-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, commission-booking record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :commission-booked (:status (store/placement db "placement-1"))))
          (is (= 1 (count (store/commission-history db))) "one draft commission-booking record")))))
  (testing "reject -> hold, nothing booked"
    (let [[db actor] (fresh)
          _ (bind-placement1! actor "t11pre")
          _ (exec-op actor "t11" {:op :commission/book :subject "placement-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t11" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/commission-history db)) "nothing booked on reject"))))

(deftest commission-book-double-booking-is-held
  (testing "booking the same placement's commission twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (bind-placement1! actor "t12pre")
          _ (exec-op actor "t12a" {:op :commission/book :subject "placement-1"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :commission/book :subject "placement-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-booking} (-> (store/ledger db) last :basis)))
      (is (not (some #{:placement-not-bound} (-> (store/ledger db) last :basis)))
          "already-booked placement is still considered bound -- see :placement-number-based check")
      (is (= 1 (count (store/commission-history db))) "still only the one earlier booking"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :placement/intake :subject "placement-1"
                          :patch {:id "placement-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "placement-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
