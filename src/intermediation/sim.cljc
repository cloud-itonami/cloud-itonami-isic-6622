(ns intermediation.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean placement through
  intake -> jurisdiction licensing/commission-cap assessment -> broker
  conflict-of-interest screening -> placement-binding proposal (always
  escalates) -> human approval -> commit, then a clean commission-
  booking (always escalates) -> human approval -> commit, then shows
  six HARD holds (a conflict-of-interest hit, a jurisdiction with no
  spec-basis, a placement bound on only one compared quote, a
  commission booked for a placement that was never bound, a commission
  rate exceeding the jurisdiction's own recorded cap, and a double-
  booking of an already-booked commission) that never reach a human at
  all, and prints the audit ledger + the draft placement-binding and
  commission-booking records."
  (:require [langgraph.graph :as g]
            [intermediation.store :as store]
            [intermediation.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== placement/intake placement-1 (JPN, clean broker, 2 quotes compared) ==")
    (println (exec! actor "t1" {:op :placement/intake :subject "placement-1"
                                :patch {:id "placement-1" :status :ready}} operator))

    (println "== jurisdiction/assess placement-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "placement-1"} operator))
    (println (approve! actor "t2"))

    (println "== conflict/screen party-2 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :conflict/screen :subject "party-2"} operator))
    (println (approve! actor "t3"))

    (println "== placement/bind placement-1 (always escalates -- actuation/bind) ==")
    (let [r (exec! actor "t4" {:op :placement/bind :subject "placement-1"} operator)]
      (println r)
      (println "-- human agent/broker approves --")
      (println (approve! actor "t4")))

    (println "== commission/book placement-1 (rate 0.15 within JPN's 0.20 cap; always escalates -- actuation/book-commission) ==")
    (let [r (exec! actor "t5" {:op :commission/book :subject "placement-1"} operator)]
      (println r)
      (println "-- human agent/broker approves --")
      (println (approve! actor "t5")))

    (println "== conflict/screen party-4 (conflict of interest -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t6" {:op :conflict/screen :subject "party-4"} operator))

    (println "== jurisdiction/assess placement-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "placement-2" :no-spec? true} operator))

    (println "== placement/bind placement-2 (only 1 quote compared, no assessment on file -> HARD hold) ==")
    (println (exec! actor "t8" {:op :placement/bind :subject "placement-2"} operator))

    (println "== commission/book placement-2 (never bound -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :commission/book :subject "placement-2"} operator))

    (println "== placement-3 fully bound (assess -> approve -> bind -> approve), rate 0.25 exceeds JPN's 0.20 cap ==")
    (exec! actor "t10a" {:op :jurisdiction/assess :subject "placement-3"} operator)
    (approve! actor "t10a")
    (exec! actor "t10b" {:op :placement/bind :subject "placement-3"} operator)
    (approve! actor "t10b")

    (println "== commission/book placement-3 (rate 0.25 exceeds the recorded 0.20 cap -> HARD hold) ==")
    (println (exec! actor "t10" {:op :commission/book :subject "placement-3"} operator))

    (println "== commission/book placement-1 AGAIN (double-booking of an already-booked commission -> HARD hold) ==")
    (println (exec! actor "t11" {:op :commission/book :subject "placement-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft placement-binding records ==")
    (doseq [r (store/binding-history db)] (println r))

    (println "== draft commission-booking records ==")
    (doseq [r (store/commission-history db)] (println r))))
