(ns intermediation.registry-test
  (:require [clojure.test :refer [deftest is]]
            [intermediation.registry :as r]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

;; ----------------------------- register-binding -----------------------------

(deftest binding-is-a-draft-not-a-real-binding
  (let [result (r/register-binding "party-1" "Insurer B" 100000 "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest binding-assigns-placement-number
  (let [result (r/register-binding "party-1" "Insurer B" 100000 "JPN" 7)]
    (is (= (get result "placement_number") "JPN-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "binding-draft"))
    (is (= (get-in result ["record" "selected_insurer"]) "Insurer B"))))

(deftest binding-validation-rules
  (is (thrown? Exception (r/register-binding "" "Insurer B" 100000 "JPN" 1)))
  (is (thrown? Exception (r/register-binding "party-1" "" 100000 "JPN" 1)))
  (is (thrown? Exception (r/register-binding "party-1" "Insurer B" -1 "JPN" 1)))
  (is (thrown? Exception (r/register-binding "party-1" "Insurer B" 100000 "" 1)))
  (is (thrown? Exception (r/register-binding "party-1" "Insurer B" 100000 "JPN" -1))))

;; ----------------------------- register-commission-booking -----------------------------

(deftest commission-booking-is-a-draft-not-a-real-payment
  (let [result (r/register-commission-booking "JPN-00000000" 0.15 15000.0 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest commission-booking-assigns-booking-number
  (let [result (r/register-commission-booking "JPN-00000000" 0.15 15000.0 "JPN" 7)]
    (is (= (get result "booking_number") "JPN-COMM-000007"))
    (is (= (get-in result ["record" "placement_number"]) "JPN-00000000"))
    (is (close? 15000.0 (get-in result ["record" "commission_amount"])))
    (is (= (get-in result ["record" "kind"]) "commission-booking-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest commission-booking-validation-rules
  (is (thrown? Exception (r/register-commission-booking "" 0.15 15000.0 "JPN" 0)))
  (is (thrown? Exception (r/register-commission-booking "JPN-00000000" -0.01 15000.0 "JPN" 0)))
  (is (thrown? Exception (r/register-commission-booking "JPN-00000000" 1.5 15000.0 "JPN" 0)))
  (is (thrown? Exception (r/register-commission-booking "JPN-00000000" 0.15 -1 "JPN" 0)))
  (is (thrown? Exception (r/register-commission-booking "JPN-00000000" 0.15 15000.0 "" 0)))
  (is (thrown? Exception (r/register-commission-booking "JPN-00000000" 0.15 15000.0 "JPN" -1))))

(deftest commission-history-is-append-only
  (let [c1 (r/register-commission-booking "JPN-00000000" 0.15 15000.0 "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-commission-booking "JPN-00000001" 0.10 5000.0 "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-COMM-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-COMM-000001" (get-in hist2 [1 "record_id"])))))
