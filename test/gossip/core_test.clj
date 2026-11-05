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
                  :c 10
                  :lease-duration-ms nil
                  :indirection? false}]
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

(deftest lease-expiry-renewal-and-stale-delivery
  (let [clock (atom 100000)
        a (ref (create-node "127.0.0.1" 1601 :c 1 :lease-duration-ms 1000))
        b (ref (create-node "127.0.0.1" 1602 :c 1 :lease-duration-ms 1000))
        c (ref (create-node "127.0.0.1" 1603 :c 1 :lease-duration-ms 1000
                            :partial-view [(get-id @b)]))
        net (network/network [a b c])]
    (with-redefs [now-ms #(deref clock)
                  clojure.core/rand-nth first
                  clojure.core/rand (constantly 0.0)
                  udp/send-message #(network/send-packet net %1 %2)]
      ;; Initial joins carry a generation and deadline through all forwarded copies.
      (send-initial (get-id @a) a (get-id @c))
      (network/drain net)
      (is (some #{(get-id @a)} (:partial-view @b)))
      (let [old-subscription (:subscription @a)]
        (swap! clock + 1000)
        (expire-subscriptions! b)
        (is (not-any? #{(get-id @a)} (:partial-view @b)))
        ;; Keep a live contact lease to exercise ordinary renewal, then lose it.
        (dosync (alter a assoc-in [:leases (get-id @c) :expires-at] (+ @clock 1000)))
        (reset! (:drop? net) #(= "renew" (first (clojure.string/split (:text %) #"--" 2))))
        (lease-tick (get-id @a) a)
        (is (:renewal-pending? @a))
        (reset! (:drop? net) (constantly false))
        (reset! (:sent net) [])
        (lease-tick (get-id @a) a)
        (network/drain net)
        (is (false? (:renewal-pending? @a)))
        (is (> (:epoch (:subscription @a)) (:epoch old-subscription)))
        ;; A renewal uses each contact edge once, with no c extra copies.
        (is (= 1 (count (filter #(and (= (get-id @c) (:sender %))
                                      (.startsWith (:text %) "subscription--")) @(:sent net)))))
        (is (some #{(get-id @a)} (:partial-view @b)))
        ;; An expired packet cannot resurrect the previous generation.
        (handle-message (get-id @b) b
          (udp/packet (udp/get-bytes-utf8
                        (str "subscription--" (gossip.wire/encode-subscription old-subscription)))
                      (udp/make-address "127.0.0.1") 1603))
        (is (= (:subscription @a) (get-in @b [:leases (get-id @a)])))
        (let [view (:partial-view @a)]
          (lease-tick (get-id @a) a)
          (is (= view (:partial-view @a)))))
      (swap! clock + 1000)
      (expire-subscriptions! b)
      (is (not-any? #{(get-id @a)} (:partial-view @b))))))

(deftest lease-option-validation
  (doseq [duration [0 -1 1.5 86400001]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (create-node "127.0.0.1" 1601 :lease-duration-ms duration)))))

(deftest indirection-selects-contact-beyond-bootstrap
  (doseq [lease-duration [nil 10000]]
    (let [make-node (fn [port next-port previous-port]
                      (ref (create-node "127.0.0.1" port :c 0 :indirection? true
                                        :lease-duration-ms lease-duration
                                        :partial-view [(str "127.0.0.1:" next-port)]
                                        :in-view [(str "127.0.0.1:" previous-port)])))
          a (make-node 1701 1702 1703)
          b (make-node 1702 1703 1701)
          c (make-node 1703 1701 1702)
          newcomer (ref (create-node "127.0.0.1" 1704 :c 0 :indirection? true
                                    :lease-duration-ms lease-duration))
          net (network/network [a b c newcomer])]
      (with-redefs [clojure.core/rand (constantly 0.0)
                    udp/send-message #(network/send-packet net %1 %2)]
        (doseq [node [a b c]] (refresh-weights (get-id @node) node))
        (network/drain net)
        (send-initial (get-id @newcomer) newcomer (get-id @a))
        (network/drain net)
        (is (= [(get-id @c)] (:partial-view @newcomer)))
        (is (some #{(get-id @newcomer)} (:in-view @c)))
        (is (not-any? #{(get-id @newcomer)} (:in-view @a)))
        (is (= 2 (count (filter #(.startsWith (:text %) "walk--") @(:sent net)))))
        ;; Every resulting directed edge has its reciprocal incoming record.
        (doseq [node [a b c newcomer]
                peer (:partial-view @node)]
          (is (some #{(get-id @node)} (:in-view @((:nodes net) peer)))))))))

(deftest weighted-selection-and-refresh
  (is (= "b" (gossip.weights/choose-peer ["a" "b"] {"a" 0.0 "b" 1.0} 0.0)))
  (is (= "a" (gossip.weights/choose-peer ["a" "b"] {"a" 0.75 "b" 0.25} 0.74)))
  (is (= "b" (gossip.weights/choose-peer ["a" "b"] {"a" 0.75 "b" 0.25} 0.76)))
  (let [node (ref (assoc (create-node "127.0.0.1" 1801
                                     :partial-view ["127.0.0.1:1802" "127.0.0.1:1803"]
                                     :in-view ["127.0.0.1:1804"])
                         :out-weights {"127.0.0.1:1802" 0.25 "obsolete" 9.0}))
        sent (atom [])]
    (with-redefs [udp/send-message (fn [_ p] (swap! sent conj (udp/get-data p)))]
      (refresh-weights nil node)
      (is (= {"127.0.0.1:1802" 0.5 "127.0.0.1:1803" 0.5} (:out-weights @node)))
      (is (= {"127.0.0.1:1804" 1.0} (:in-weights @node)))
      (is (= 3 (count @sent))))))

(deftest malformed-extension-messages-do-not-change-membership
  (let [node (ref (create-node "127.0.0.1" 1901))]
    (doseq [text ["join--bad" "subscription--127.0.0.1:1902|bad|3"
                  "walk--127.0.0.1:1902|0|0|127.0.0.1:1903|99999|join"
                  "weight--out|NaN" "weight--out|-0.1" "weight--out|Infinity"
                  "weight--in|0.5|extra" "contact--bad"]]
      (handle-message nil node
        (udp/packet (udp/get-bytes-utf8 text) (udp/make-address "127.0.0.1") 1902)))
    (is (empty? (:partial-view @node)))
    (is (empty? (:in-view @node)))
    (is (nil? (:in-weights @node)))))

(deftest indirection-stops-at-empty-bootstrap
  (let [a (ref (create-node "127.0.0.1" 1951 :indirection? true))
        b (ref (create-node "127.0.0.1" 1952 :indirection? true))
        net (network/network [a b])]
    (with-redefs [udp/send-message #(network/send-packet net %1 %2)]
      (send-initial (get-id @a) a (get-id @b))
      (network/drain net)
      (is (= [(get-id @b)] (:partial-view @a)))
      (is (= [(get-id @a)] (:in-view @b))))))

(deftest group-recovers-after-simultaneous-lease-expiry
  (doseq [indirection? [false true]]
    (let [clock (atom 1000000)
          nodes (mapv #(ref (create-node "127.0.0.1" % :c 0
                                         :lease-duration-ms 1000 :indirection? indirection?))
                      [2001 2002 2003])
          [a b c] nodes
          net (network/network nodes)]
      (with-redefs [now-ms #(deref clock)
                    clojure.core/rand (constantly 0.0)
                    clojure.core/rand-nth first
                    udp/send-message #(network/send-packet net %1 %2)]
        (send-initial (get-id @b) b (get-id @a))
        (network/drain net)
        (send-initial (get-id @c) c (get-id @b))
        (network/drain net)
        (dotimes [generation 3]
          (swap! clock + 1000)
          (doseq [node nodes] (expire-subscriptions! node))
          (doseq [node nodes] (lease-tick (get-id @node) node))
          (network/drain net)
          (doseq [node nodes]
            (is (seq (:partial-view @node)))
            (is (false? (:renewal-pending? @node))))
          (let [message (str "generation-" generation)]
            (gossip (get-id @a) a message)
            (network/drain net)
            (is (every? #(contains? (:received-messages @%) message) nodes))))))))

(deftest replacement-rejects-self-duplicate-and-replayed-edges
  (let [node (ref (create-node "127.0.0.1" 2101
                               :partial-view ["127.0.0.1:2102" "127.0.0.1:2103"]))]
    (handle-receive-replace node "127.0.0.1:2102" "127.0.0.1:2103")
    (is (= ["127.0.0.1:2103"] (:partial-view @node)))
    (handle-receive-replace node "127.0.0.1:2102" "127.0.0.1:2104")
    (is (= ["127.0.0.1:2103"] (:partial-view @node)))
    (handle-receive-replace node "127.0.0.1:2103" (get-id @node))
    (is (empty? (:partial-view @node)))))

(deftest weights-ignore-removed-edges
  (let [node (ref (create-node "127.0.0.1" 2201
                               :partial-view ["127.0.0.1:2202"]
                               :in-view ["127.0.0.1:2203"]))
        deliver (fn [port payload]
                  (handle-message nil node
                    (udp/packet (udp/get-bytes-utf8 payload)
                                (udp/make-address "127.0.0.1") port)))]
    (deliver 2202 "weight--in|0.25")
    (deliver 2203 "weight--out|0.75")
    (is (= {"127.0.0.1:2202" 0.25} (:out-weights @node)))
    (is (= {"127.0.0.1:2203" 0.75} (:in-weights @node)))
    (handle-receive-remove node "127.0.0.1:2202")
    (deliver 2202 "weight--in|0.5")
    (is (empty? (:partial-view @node)))
    (is (nil? (get-in @node [:out-weights "127.0.0.1:2202"])))
    (deliver 2204 "weight--out|0.5")
    (is (nil? (get-in @node [:in-weights "127.0.0.1:2204"])))))

(deftest initial-views-have-no-self-or-duplicate-edges
  (let [node (create-node "127.0.0.1" 2301
                           :in-view ["127.0.0.1:2301" "127.0.0.1:2302" "127.0.0.1:2302"]
                           :partial-view ["127.0.0.1:2301" "127.0.0.1:2302" "127.0.0.1:2302"])]
    (is (= ["127.0.0.1:2302"] (:in-view node)))
    (is (= ["127.0.0.1:2302"] (:partial-view node)))))
