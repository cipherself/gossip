(ns gossip.core-test
  (:require [clojure.test :refer :all]
            [gossip.core :refer :all]
            [udp-wrapper.core :as udp]))

(deftest node-creation
  (let [node (create-node "127.0.0.1" 1024)
        node-map {:ip "127.0.0.1"
                  :port 1024
                  :in-view []
                  :partial-view []
                  :heartbeat-counter 5
                  :forward-counters {}
                  :received-messages #{}}]
    (is (= node node-map))))

(deftest node-id
  (is (= (get-id {:ip "127.0.0.1" :port 1024}) "127.0.0.1:1024")))

(deftest post-initial
  (let [node (ref (create-node "127.0.0.1" 1024 :partial-view ["192.168.1.1:6000"]))]
    (testing "contact-addition"
      (post-send-initial node "10.0.0.1:1000")
      (is (= ["192.168.1.1:6000" "10.0.0.1:1000"] (:partial-view @node))))))


(deftest heartbeat
  (let [node (ref (create-node "127.0.0.1" 1024))]
    (handle-receive-heartbeat node)
    (is (= (:heartbeat-counter @node) 10))))

(deftest receive-replace
  (let [node (ref (create-node "127.0.0.1" 1024 :partial-view ["192.168.1.1:5000"]))]
    (handle-receive-replace node "192.168.1.1:5000" "10.0.0.1:3000")
    (is (= (:partial-view @node) ["10.0.0.1:3000"]))))

(deftest gossip-stops-in-three-node-ring
  (let [ports [1001 1002 1003]
        nodes (into {} (map (fn [port next-port]
                             [port (ref (create-node "127.0.0.1" port
                                          :partial-view [(str "127.0.0.1:" next-port)]))])
                           ports [1002 1003 1001]))
        pending (atom clojure.lang.PersistentQueue/EMPTY)
        destinations (atom [])]
    ;; Queue real protocol packets instead of using sockets or background threads.
    (with-redefs [udp/send-message (fn [_ packet]
                                    (swap! pending conj packet)
                                    (swap! destinations conj (udp/get-port packet)))]
      (gossip nil (nodes 1001) "ring-message")
      ;; Bound deliveries so a regression fails instead of looping forever.
      (dotimes [_ 10]
        (when-let [packet (peek @pending)]
          (swap! pending pop)
          (handle-message nil (nodes (udp/get-port packet)) packet)))
      (is (empty? @pending))
      (is (= [1002 1003 1001 1002] @destinations))
      (doseq [node (vals nodes)]
        (is (= #{"ring-message"} (:received-messages @node)))))))

(deftest declined-subscription-is-forwarded
  (let [subscriber-id "127.0.0.1:1001"
        peer-id "127.0.0.1:1003"
        node (ref (create-node "127.0.0.1" 1002 :partial-view [peer-id]))
        sent (atom [])]
    ;; A draw of 0.99 deterministically declines the 1/2 acceptance probability.
    (with-redefs [clojure.core/rand (constantly 0.99)
                  udp/send-message (fn [_ packet]
                                     (swap! sent conj [(udp/get-ip packet)
                                                       (udp/get-port packet)
                                                       (udp/get-data packet)]))]
      (handle-message nil node
                      (udp/packet (udp/get-bytes-utf8 (str "forward--" subscriber-id))
                                  (udp/make-address "127.0.0.1") 1004))
      (is (= [["127.0.0.1" 1003 (str "forward--" subscriber-id)]] @sent))
      (is (= [peer-id] (:partial-view @node)))
      (is (= 0 (get-in @node [:forward-counters (keyword subscriber-id)]))))))
