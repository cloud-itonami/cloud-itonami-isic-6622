(ns intermediation.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:placement/bind`/`:commission/book` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [intermediation.phase :as phase]))

(deftest placement-bind-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real placement binding"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :placement/bind))
          (str "phase " n " must not auto-commit :placement/bind")))))

(deftest commission-book-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-books a real commission"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :commission/book))
          (str "phase " n " must not auto-commit :commission/book")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:placement/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :placement/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :placement/bind} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :commission/book} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :placement/intake} :commit)))))
