(ns gossip.test-runner
  (:require [clojure.test :as test]
            [gossip.core-test]
            [gossip.integration-test]
            [gossip.properties-test]
            [gossip.simulation-test]
            [gossip.utils-test]
            [gossip.wire-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'gossip.core-test
                                          'gossip.integration-test
                                          'gossip.properties-test
                                          'gossip.simulation-test
                                          'gossip.utils-test
                                          'gossip.wire-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
