(ns gossip.core-test
  (:require [clojure.test :refer :all]
            [gossip.core :refer :all]))

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
