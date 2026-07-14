(ns wasm.commission-cap-test
  "Hosts wasm/commission_cap.wasm (compiled from wasm/commission_cap.kotoba,
  see wasm/README.md) via kototama.tender -- proves intermediation.governor's
  commission-rate-exceeds-cap check (`commission-rate-exceeds-cap-violations`
  in src/intermediation/governor.cljc, decided by intermediation.kernels.
  gate/rate-exceeds-cap) runs as a real WASM guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the two real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/commission_cap.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/commission_cap.wasm"))))

(defn- run-rate-within-cap? [commission-rate-bps cap-bps]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 commission-rate-bps)
    (.writeI32 memory 4 cap-bps)
    (tender/call-main instance)))

(deftest commission-cap-wasm-approves-well-within-cap
  (testing "commission rate well below the jurisdiction cap -> within cap"
    (is (= 1 (run-rate-within-cap? 1000 2000)))))

(deftest commission-cap-wasm-rejects-exceeding-cap
  (testing "commission rate above the jurisdiction cap -> exceeds cap"
    (is (= 0 (run-rate-within-cap? 2500 2000)))))

(deftest commission-cap-wasm-approves-exact-boundary
  (testing "commission rate exactly equal to the cap -> within cap (<=, ceiling not open bound)"
    (is (= 1 (run-rate-within-cap? 2000 2000)))))

(deftest commission-cap-wasm-rejects-boundary-plus-one
  (testing "commission rate one basis point above the cap -> exceeds cap"
    (is (= 0 (run-rate-within-cap? 2001 2000)))))
