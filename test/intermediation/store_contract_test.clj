(ns intermediation.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` / `cloud-itonami-isic-6512`'s
  `casualty.store-contract-test` / `cloud-itonami-isic-6621`'s
  `adjustment.store-contract-test` for the same pattern on the sibling
  actors."
  (:require [clojure.test :refer [deftest is testing]]
            [intermediation.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "party-1" (:customer (store/placement s "placement-1"))))
      (is (= "JPN" (:jurisdiction (store/placement s "placement-1"))))
      (is (= "party-2" (:broker (store/placement s "placement-1"))))
      (is (= 2 (count (:quotes (store/placement s "placement-1")))))
      (is (= "田中 一郎" (:name (store/party s "party-2"))))
      (is (false? (:conflict-hit? (store/party s "party-2"))))
      (is (true? (:conflict-hit? (store/party s "party-4"))))
      (is (= ["placement-1" "placement-2" "placement-3"] (mapv :id (store/all-placements s))))
      (is (nil? (store/conflict-of s "party-2")))
      (is (nil? (store/assessment-of s "placement-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/binding-history s)))
      (is (= [] (store/commission-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (zero? (store/commission-sequence s "JPN")))
      (is (false? (store/commission-already-booked? s "JPN-00000000"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :placement/upsert
                                 :value {:id "placement-1" :status :ready}})
        (is (= :ready (:status (store/placement s "placement-1"))))
        (is (= "party-1" (:customer (store/placement s "placement-1"))) "customer preserved"))
      (testing "assessment / conflict payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["placement-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "placement-1")))
        (store/commit-record! s {:effect :conflict/set :path ["party-2"]
                                 :payload {:party-id "party-2" :verdict :clear}})
        (is (= {:party-id "party-2" :verdict :clear} (store/conflict-of s "party-2"))))
      (testing "binding drafts a placement record and advances the sequence"
        (store/commit-record! s {:effect :placement/mark-bound :path ["placement-1"]})
        (is (= "JPN-00000000" (get (first (store/binding-history s)) "record_id")))
        (is (= "binding-draft" (get (first (store/binding-history s)) "kind")))
        (is (= :bound (:status (store/placement s "placement-1"))))
        (is (= 1 (count (store/binding-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "commission booking drafts a commission record, advances the sequence, and computes the amount from the placement's own rate/premium"
        (store/commit-record! s {:effect :commission/mark-booked :path ["placement-1"]})
        (is (= "JPN-COMM-000000" (get (first (store/commission-history s)) "record_id")))
        (is (= "commission-booking-draft" (get (first (store/commission-history s)) "kind")))
        (is (close? 15000.0 (get (first (store/commission-history s)) "commission_amount"))
            "100,000 premium * 0.15 rate")
        (is (= :commission-booked (:status (store/placement s "placement-1"))))
        (is (= 1 (count (store/commission-history s))))
        (is (= 1 (store/commission-sequence s "JPN")))
        (is (true? (store/commission-already-booked? s "JPN-00000000")))
        (is (false? (store/commission-already-booked? s "JPN-00000001"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/placement s "nope")))
    (is (= [] (store/all-placements s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/binding-history s)))
    (is (= [] (store/commission-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (is (zero? (store/commission-sequence s "JPN")))
    (store/with-placements s {"x" {:id "x" :customer "p" :broker "b" :jurisdiction "JPN"
                                   :quotes [] :selected-insurer "Insurer X"
                                   :selected-premium 0 :selected-commission-rate 0
                                   :status :intake}})
    (is (= "p" (:customer (store/placement s "x"))))))
