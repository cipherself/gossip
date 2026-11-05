(ns gossip.simulation-test
  (:require [clojure.test :refer :all]
            [gossip.simulation :as simulation]))

(deftest lease-partition-heals-with-continued-maintenance
  (let [result (simulation/run-scenario
                 {:size 100 :seed 3 :mode :random :leases? true
                  :recovery-rounds 60 :settle-rounds 0})
        recovery (:recovery result)]
    (is (false? (get-in result [:after :strongly-connected?])))
    (is (<= 1 (:connected-at-round recovery) 60))
    (is (true? (get-in recovery [:after :strongly-connected?])))
    (is (= 100 (:gossip-reached recovery)))
    (is (zero? (:edge-mismatches result)))))

(deftest recovery-options-are-validated
  (doseq [options [{:recovery-rounds -1}
                   {:recovery-rounds 1.5}
                   {:recovery-rounds 10001}
                   {:recovery-step-ms 0}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (simulation/run-scenario options)))))
