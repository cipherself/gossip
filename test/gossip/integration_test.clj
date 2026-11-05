(ns gossip.integration-test
  (:require [clojure.test :refer :all]
            [gossip.core :as gossip]
            [gossip.utils :as utils]
            [udp-wrapper.core :as udp]))

(defn- eventually [predicate]
  (let [deadline (+ (System/nanoTime) 2000000000)]
    (loop []
      (cond
        (predicate) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest real-udp-ring-and-receiver-recovery
  (with-open [a (udp/create-udp-server 0 {:so-timeout 100})
              b (udp/create-udp-server 0 {:so-timeout 100})
              c (udp/create-udp-server 0 {:so-timeout 100})]
    (let [sockets [a b c]
          nodes (mapv (fn [socket peer]
                        (ref (gossip/create-node "127.0.0.1" (.getLocalPort socket)
                               :partial-view [(str "127.0.0.1:" (.getLocalPort peer))])))
                      sockets [b c a])
          errors (atom [])
          counts (atom [0 0 0])
          receivers (mapv (fn [index socket node]
                            (utils/receive socket (udp/empty-packet 65507) node
                              (fn [socket node packet]
                                (swap! counts update-in [index] inc)
                                (gossip/handle-message socket node packet))
                              {:on-error #(swap! errors conj %)}))
                          (range) sockets nodes)]
      (try
        ;; A short malformed packet precedes a longer UTF-8 payload on one buffer.
        (udp/send-message a (udp/packet (udp/get-bytes-utf8 "unknown")
                                        (udp/make-address "127.0.0.1") (.getLocalPort b)))
        (let [message "A longer message λ--with separators"]
          (gossip/gossip a (nodes 0) message)
          (is (eventually #(every? (fn [node] (contains? (:received-messages @node) message)) nodes)))
          (is (eventually #(= [1 3 1] @counts)))
          (Thread/sleep 100)
          (is (= [1 3 1] @counts))
          (is (empty? @errors)))
        (finally
          (doseq [socket sockets] (.close socket))
          (doseq [receiver receivers]
            (is (nil? (deref receiver 2000 ::timeout)))))))))
