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

(defspec new-arc-weights-are-initialized-when-the-edge-is-created
  {:num-tests 100 :seed 2026110503}
  (prop/for-all [first-weight (gen/choose 1 100)
                 second-weight (gen/choose 1 100)]
    (let [a (ref (assoc (gossip/create-node "127.0.0.1" 2701 :indirection? true
                                          :partial-view ["127.0.0.1:2702" "127.0.0.1:2703"])
                        :out-weights {"127.0.0.1:2702" (/ first-weight 100.0)
                                      "127.0.0.1:2703" (/ second-weight 100.0)}))]
      (with-redefs [clojure.core/rand (constantly 0.0)
                    udp/send-message (fn [& _])]
        (gossip/handle-receive-forward nil a "127.0.0.1:2704")
        (< (Math/abs (- (/ (+ first-weight second-weight) 200.0)
                           (get-in @a [:out-weights "127.0.0.1:2704"]))) 1.0e-12)))))

(defspec obsolete-arc-weights-do-not-survive-edge-removal
  {:num-tests 100 :seed 2026110504}
  (prop/for-all [weight (gen/choose 0 100)]
    (let [a (ref (assoc (gossip/create-node "127.0.0.1" 2801
                                          :partial-view ["127.0.0.1:2802"])
                        :out-weights {"127.0.0.1:2802" (/ weight 100.0)}))]
      (gossip/handle-receive-remove a "127.0.0.1:2802")
      (not (contains? (:out-weights @a) "127.0.0.1:2802")))))

(defspec lease-lifecycle-preserves-reciprocal-records-after-delivery
  {:num-tests 100 :seed 2026110505}
  (prop/for-all [durations (gen/vector (gen/choose 100 1000) 3 7)
                 indirection? gen/boolean
                 seed (gen/choose 1 100000)
                 actions (gen/vector
                           (gen/tuple (gen/elements [:advance :maintain :replay])
                                      (gen/choose 0 1000)) 1 15)]
    (network/with-seed seed
      (fn []
        (let [clock (atom 100000)
              nodes (mapv (fn [index duration]
                            (ref (gossip/create-node "127.0.0.1" (+ 2900 index) :c 0
                                     :indirection? indirection? :lease-duration-ms duration)))
                          (range) durations)
              net (network/network nodes)
              bootstrap (first nodes)
              expire-all #(doseq [node nodes] (gossip/expire-subscriptions! node))]
          (with-redefs [gossip/now-ms #(deref clock)
                        udp/send-message #(network/send-packet net %1 %2)]
            (doseq [node (rest nodes)]
              (gossip/send-initial (gossip/get-id @node) node (gossip/get-id @bootstrap))
              (network/drain net))
            (let [old (:subscription @(nodes 1))]
              (and (membership-consistent? nodes)
                   (every? true?
                     (for [[action elapsed] actions]
                       (do
                         (case action
                           :advance (swap! clock + elapsed)
                           :maintain (doseq [node nodes] (gossip/lease-tick (gossip/get-id @node) node))
                           :replay (gossip/send-subscription (gossip/get-id @(nodes 1))
                                                             (gossip/get-id @bootstrap) old))
                         (network/drain net)
                         (expire-all)
                         (membership-consistent? nodes))))))))))))
