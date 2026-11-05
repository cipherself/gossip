(ns gossip.core-test
  (:require [clojure.test :refer :all]
            [gossip.core :refer :all]
            [udp-wrapper.core :as udp]
            [gossip.network :as network]))

(deftest node-creation
  (let [node (create-node "127.0.0.1" 1024)
        node-map {:ip "127.0.0.1"
                  :port 1024
                  :in-view []
                  :partial-view []
                  :heartbeat-counter 5
                  :forward-counters {}
                  :received-messages #{}
                  :c 10}]
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

(deftest message-validation
  (let [node (ref (create-node "127.0.0.1" 1001))
        pkt (fn [text] (udp/packet (udp/get-bytes-utf8 text)
                                  (udp/make-address "127.0.0.1") 1002))]
    (doseq [message ["unknown" "forward--bad-id" "replace--127.0.0.1:99999"
                     "initial--extra" "heartbeat--extra" "gossip"]]
      (is (nil? (handle-message nil node (pkt message)))))
    (is (= #{} (:received-messages @node)))
    (doseq [message ["hello--world" "" "λ"]]
      (handle-message nil node (pkt (str "gossip--" message))))
    (is (= #{"hello--world" "" "λ"} (:received-messages @node)))
    (doseq [[ip port] [["999.0.0.1" 1234] ["127.0.0.1" 0]
                       ["127.0.0.1" 65536] ["bad host" 1234]]]
      (is (thrown? clojure.lang.ExceptionInfo (create-node ip port))))
    (is (= "[::1]:1234" (get-id (create-node "::1" 1234))))))

(deftest membership-and-departure
  (let [a (ref (create-node "127.0.0.1" 1101 :c 0))
        b (ref (create-node "127.0.0.1" 1102 :c 0))
        c (ref (create-node "127.0.0.1" 1103 :c 0))
        net (network/network [a b c])]
    (with-redefs [udp/send-message #(network/send-packet net %1 %2)
                  clojure.core/rand (constantly 0.0)]
      (send-initial (get-id @a) a (get-id @b))
      (network/drain net)
      (send-initial (get-id @c) c (get-id @a))
      (network/drain net)
      (doseq [node [a b c]
              peer (:partial-view @node)]
        (is (some #{(get-id @node)} (:in-view @((:nodes net) peer)))))
      (handle-receive-forward (get-id @a) a (get-id @b))
      (network/drain net)
      (is (= (count (:partial-view @a)) (count (distinct (:partial-view @a)))))
      (unsubscribe (get-id @a) a)
      (network/drain net)
      (is (empty? (:partial-view @a)))
      (is (empty? (:in-view @a)))
      (doseq [node [b c]]
        (is (not-any? #{(get-id @a)} (concat (:partial-view @node) (:in-view @node))))))))

(deftest unsubscribe-with-empty-outgoing-view
  (let [node (ref (create-node "127.0.0.1" 1201 :in-view ["127.0.0.1:1202"]))
        sent (atom [])]
    (with-redefs [udp/send-message (fn [_ p] (swap! sent conj (udp/get-data p)))]
      (unsubscribe nil node)
      (is (= ["remove"] @sent))
      (is (empty? (:in-view @node))))))

(deftest configurable-subscription-copies-and-departure-removals
  (doseq [parameter [0 1 3]]
    (let [incoming (mapv #(str "127.0.0.1:" %) (range 1301 1307))
          node (ref (create-node "127.0.0.1" 1300 :c parameter
                                 :partial-view ["127.0.0.1:1310"]
                                 :in-view incoming))
          sent (atom [])]
      (with-redefs [udp/send-message (fn [_ p] (swap! sent conj (udp/get-data p)))]
        (handle-receive-initial nil node "127.0.0.1:1320")
        (is (= (inc parameter) (count @sent)))
        (reset! sent [])
        (unsubscribe nil node)
        (is (= (inc parameter) (count (filter #{"remove"} @sent))))
        (is (= (- 7 (inc parameter)) (count (filter #(.startsWith % "replace--") @sent)))))))
  (doseq [parameter [-1 1.5 1001]]
    (is (thrown? clojure.lang.ExceptionInfo (create-node "127.0.0.1" 1300 :c parameter)))))

(deftest isolation-recovery-retries-after-packet-loss
  (let [a (ref (create-node "127.0.0.1" 1401 :heartbeat-counter 1
                             :partial-view ["127.0.0.1:1402"]))
        b (ref (create-node "127.0.0.1" 1402))
        net (network/network [a b])]
    (with-redefs [udp/send-message #(network/send-packet net %1 %2)]
      (reset! (:drop? net) (constantly true))
      (heartbeat-tick (get-id @a) a)
      (is (empty? (:in-view @b)))
      (reset! (:drop? net) (constantly false))
      (dotimes [_ 10] (heartbeat-tick (get-id @a) a))
      (network/drain net)
      (is (= [(get-id @a)] (:in-view @b)))
      (is (= [(get-id @b)] (:partial-view @a))))))

(deftest duplicate-and-self-subscriptions-are-bounded
  (let [node (ref (create-node "127.0.0.1" 1501 :partial-view ["127.0.0.1:1502"]))
        sent (atom [])]
    (with-redefs [clojure.core/rand (constantly 0.0)
                  udp/send-message (fn [_ p] (swap! sent conj (udp/get-data p)))]
      (dotimes [_ 20] (handle-receive-forward nil node (get-id @node)))
      (is (= 10 (count @sent)))
      (is (= ["127.0.0.1:1502"] (:partial-view @node))))))
