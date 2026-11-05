(ns gossip.properties-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gossip.core :as gossip]
            [gossip.network :as network]
            [udp-wrapper.core :as udp]))

(defn- membership-consistent? [nodes]
  (let [by-id (into {} (map (fn [node] [(gossip/get-id @node) @node]) nodes))]
    (every?
      (fn [node]
        (let [id (gossip/get-id node)]
          (and (= (count (:partial-view node)) (count (distinct (:partial-view node))))
               (= (count (:in-view node)) (count (distinct (:in-view node))))
               (not-any? #{id} (concat (:partial-view node) (:in-view node)))
               (every? (fn [peer]
                         (and (some #{id} (:in-view (by-id peer)))
                              (= (get-in node [:leases peer])
                                 (get-in (by-id peer) [:incoming-leases id]))))
                       (:partial-view node))
               (every? (fn [peer] (some #{id} (:partial-view (by-id peer))))
                       (:in-view node)))))
      (vals by-id))))

(defspec leased-edges-share-the-destinations-deadline
  {:num-tests 150 :seed 2026110501}
  (prop/for-all [first-duration (gen/choose 100 1000)
                 second-duration (gen/choose 100 1000)
                 duplicates (gen/choose 0 5)
                 advances (gen/vector (gen/choose 0 500) 1 8)]
    (let [clock (atom 100000)
          a (ref (gossip/create-node "127.0.0.1" 2401 :c 0
                                     :lease-duration-ms first-duration))
          b (ref (gossip/create-node "127.0.0.1" 2402 :c 0
                                     :lease-duration-ms second-duration))
          nodes [a b]
          net (network/network nodes)]
      (with-redefs [gossip/now-ms #(deref clock)
                    clojure.core/rand (constantly 0.0)
                    clojure.core/rand-nth first
                    udp/send-message #(network/send-packet net %1 %2)]
        (gossip/send-initial (gossip/get-id @b) b (gossip/get-id @a))
        (network/drain net)
        (gossip/send-subscription (gossip/get-id @b) (gossip/get-id @a) (:subscription @b))
        (network/drain net)
        (let [old (:subscription @b)]
          (and
            (membership-consistent? nodes)
            (every? true?
              (for [advance advances]
                (do
                  (swap! clock + advance)
                  ;; Replay the previous generation, including after its expiry.
                  (dotimes [_ duplicates]
                    (gossip/send-subscription (gossip/get-id @b) (gossip/get-id @a) old))
                  (network/drain net)
                  (doseq [node nodes] (gossip/expire-subscriptions! node))
                  (membership-consistent? nodes))))))))))

(deftest unequal-deadlines-do-not-delete-a-live-incoming-edge
  (let [clock (atom 100000)
        a (ref (gossip/create-node "127.0.0.1" 2401 :c 0 :lease-duration-ms 1000))
        b (ref (gossip/create-node "127.0.0.1" 2402 :c 0 :lease-duration-ms 2000))
        net (network/network [a b])]
    (with-redefs [gossip/now-ms #(deref clock)
                  clojure.core/rand (constantly 0.0)
                  udp/send-message #(network/send-packet net %1 %2)]
      (gossip/send-initial (gossip/get-id @b) b (gossip/get-id @a))
      (network/drain net)
      (gossip/send-subscription (gossip/get-id @b) (gossip/get-id @a) (:subscription @b))
      (network/drain net)
      (reset! clock 101000)
      (doseq [node [a b]] (gossip/expire-subscriptions! node))
      (is (= [(gossip/get-id @b)] (:partial-view @a)))
      (is (= [(gossip/get-id @a)] (:in-view @b)))
      (is (membership-consistent? [a b])))))

(defspec empty-bootstrap-creates-a-connected-start
  {:num-tests 100 :seed 2026110502}
  (prop/for-all [size (gen/choose 2 20)
                 parameter (gen/choose 0 2)
                 indirection? gen/boolean
                 leases? gen/boolean
                 seed (gen/choose 1 100000)]
    (network/with-seed seed
      (fn []
        (let [nodes (mapv #(ref (gossip/create-node "127.0.0.1" (+ 2500 %)
                                   :c parameter :indirection? indirection?
                                   :lease-duration-ms (when leases? 10000))) (range size))
              bootstrap (first nodes)
              net (network/network nodes)]
          (with-redefs [gossip/now-ms (constantly 100000)
                        udp/send-message #(network/send-packet net %1 %2)]
            (gossip/send-initial (gossip/get-id @(nodes 1)) (nodes 1) (gossip/get-id @bootstrap))
            (network/drain net)
            (let [connected-pair? (and (= [(gossip/get-id @(nodes 1))] (:partial-view @bootstrap))
                                       (= [(gossip/get-id @bootstrap)] (:partial-view @(nodes 1))))]
              (doseq [node (drop 2 nodes)]
                (gossip/send-initial (gossip/get-id @node) node (gossip/get-id @bootstrap))
                (network/drain net))
              (and connected-pair?
                   (every? #(seq (:partial-view @%)) nodes)
                   (membership-consistent? nodes)))))))))

(deftest first-subscriber-can-receive-from-bootstrap
  (doseq [indirection? [false true]
          duration [nil 10000]]
    (let [a (ref (gossip/create-node "127.0.0.1" 2601 :c 0
                                   :indirection? indirection? :lease-duration-ms duration))
          b (ref (gossip/create-node "127.0.0.1" 2602 :c 0
                                   :indirection? indirection? :lease-duration-ms duration))
          net (network/network [a b])]
      (with-redefs [gossip/now-ms (constantly 100000)
                    udp/send-message #(network/send-packet net %1 %2)]
        (gossip/send-initial (gossip/get-id @b) b (gossip/get-id @a))
        (network/drain net)
        (gossip/gossip (gossip/get-id @a) a "from-bootstrap")
        (network/drain net)
        (is (contains? (:received-messages @b) "from-bootstrap"))
        (is (membership-consistent? [a b]))))))
