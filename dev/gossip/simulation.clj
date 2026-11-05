(ns gossip.simulation
  "Reproducible lossless protocol experiments, separate from regression tests."
  (:require [clojure.edn :as edn]
            [gossip.core :as gossip]
            [gossip.network :as network]
            [gossip.weights :as weights]
            [udp-wrapper.core :as udp]))

(defn- view-stats [nodes]
  (let [sizes (mapv #(count (:partial-view @%)) nodes)
        mean (/ (reduce + sizes) (double (count nodes)))]
    {:mean mean
     :sd (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) sizes)) (count nodes)))
     :max (apply max sizes)
     :empty (count (filter zero? sizes))}))

(defn- edge-mismatches [nodes by-id]
  (count
    (for [node nodes
          peer (:partial-view @node)
          :when (or (not (some #{(gossip/get-id @node)} (:in-view @(by-id peer))))
                    (not= (get-in @node [:leases peer])
                          (get-in @(by-id peer) [:incoming-leases (gossip/get-id @node)])))]
      peer)))

(defn- transition [rows distribution]
  (reduce (fn [result [source row]]
            (reduce (fn [result [target probability]]
                      (update-in result [target] (fnil + 0.0)
                                 (* (get distribution source 0.0) probability)))
                    result row))
          {} rows))

(defn- distance [left right ids]
  (let [left-failure (max 0.0 (- 1.0 (reduce + 0.0 (vals left))))
        right-failure (max 0.0 (- 1.0 (reduce + 0.0 (vals right))))]
    (* 0.5 (+ (Math/abs (- left-failure right-failure))
              (reduce + (map #(Math/abs (- (get left % 0.0) (get right % 0.0))) ids))))))

(defn- routing-stats [nodes bootstrap]
  (let [ids (mapv #(gossip/get-id @%) nodes)
        uniform (zipmap ids (repeat (/ 1.0 (count ids))))
        rows (into {} (map (fn [node]
                             [(gossip/get-id @node)
                              (weights/normalize (weights/reconcile (:partial-view @node)
                                                                    (:out-weights @node)))]) nodes))
        columns (reduce (fn [result row] (merge-with + result row)) {} (vals rows))
        hops (* 2 (count (:partial-view @bootstrap)))
        short (nth (iterate #(transition rows %) {(gossip/get-id @bootstrap) 1.0}) hops)
        long (nth (iterate #(transition rows %) uniform) 500)
        single-neighbors (frequencies (keep (fn [node]
                                               (when (= 1 (count (:partial-view @node)))
                                                 (first (:partial-view @node)))) nodes))]
    {:bootstrap-degree (count (:partial-view @bootstrap))
     :walk-hops hops
     :short-walk-support (count (filter pos? (vals short)))
     :short-walk-failure (max 0.0 (- 1.0 (reduce + 0.0 (vals short))))
     :long-walk-failure (max 0.0 (- 1.0 (reduce + 0.0 (vals long))))
     :column-error (apply max (map #(Math/abs (- 1.0 (get columns % 0.0))) ids))
     :short-walk-tv (distance short uniform ids)
     :long-walk-tv (distance long uniform ids)
     ;; Two degree-one rows targeting one column cannot both have column mass 1.
     :single-neighbor-collisions (reduce + (map #(max 0 (dec %)) (vals single-neighbors)))}))

(defn- strongly-connected? [nodes]
  (let [ids (mapv #(gossip/get-id @%) nodes)
        outgoing (into {} (map (fn [node] [(gossip/get-id @node) (:partial-view @node)]) nodes))
        incoming (reduce (fn [result [source peers]]
                           (reduce #(update-in %1 [%2] (fnil conj []) source) result peers))
                         {} outgoing)
        reachable (fn [edges]
                    (loop [pending [(first ids)] seen #{}]
                      (if-let [id (peek pending)]
                        (if (seen id)
                          (recur (pop pending) seen)
                          (recur (into (pop pending) (get edges id)) (conj seen id)))
                        (count seen))))]
    (= (count nodes) (reachable outgoing) (reachable incoming))))

(defn run-scenario
  [{:keys [size seed mode leases? seed-size weight-rounds settle-rounds]
    :or {size 100 seed 1 mode :indirect leases? false seed-size 5
         weight-rounds 1 settle-rounds 20}}]
  (let [clock (atom 100000)
        durations (java.util.Random. (+ seed 1000000))
        nodes (mapv #(ref (gossip/create-node "127.0.0.1" (+ 10000 %) :c 0
                                  :indirection? (= mode :indirect)
                                  :lease-duration-ms (when leases? (+ 10000 (.nextInt durations 10000)))))
                    (range size))
        ids (mapv #(gossip/get-id @%) nodes)
        net (network/network nodes)
        drain #(do (network/drain net 1000000) (reset! (:sent net) []))
        refresh (fn [active rounds]
                  (dotimes [_ rounds]
                    (doseq [node active]
                      (gossip/refresh-weights (gossip/get-id @node) node)
                      (drain))))]
    (when-not (<= 1 seed-size size 10000)
      (throw (ex-info "Require 1 <= seed-size <= size <= 10000" {})))
    (network/with-seed seed
      (fn []
        (with-redefs [gossip/now-ms #(deref clock)
                      udp/send-message #(network/send-packet net %1 %2)]
          (when (> seed-size 1)
            (doseq [i (range seed-size)]
              (dosync (alter (nodes i) assoc
                             :partial-view [(ids (mod (inc i) seed-size))]
                             :in-view [(ids (mod (dec i) seed-size))]))))
          (when leases?
            (doseq [i (range seed-size)]
              (dosync (alter (nodes i) assoc :subscription
                             {:id (ids i) :epoch @clock
                              :expires-at (+ @clock (:lease-duration-ms @(nodes i)))})))
            (doseq [node (take seed-size nodes)
                    peer (:partial-view @node)]
              (dosync (alter node assoc-in [:leases peer] (:subscription @((:nodes net) peer)))))
            (doseq [node (take seed-size nodes)
                    peer (:in-view @node)]
              (dosync (alter node assoc-in [:incoming-leases peer] (:subscription @node)))))
          (doseq [i (range seed-size size)]
            (swap! clock inc)
            (when (and (= mode :indirect) (zero? (mod i 10)))
              (refresh (take i nodes) weight-rounds))
            (let [contact (if (= mode :random) (rand-nth (subvec ids 0 i)) (ids 0))]
              (gossip/send-initial (ids i) (nodes i) contact)
              (drain)))
          (let [before (assoc (view-stats nodes) :strongly-connected? (strongly-connected? nodes))
                routing-before (when (= mode :indirect) (routing-stats nodes (nodes 0)))]
            (when leases?
              ;; Staggered deadlines produce one renewal per original subscription.
              (doseq [[expiry node] (sort-by first
                                      (map (fn [node] [(:expires-at (:subscription @node)) node]) nodes))]
                (reset! clock expiry)
                (doseq [peer nodes] (gossip/expire-subscriptions! peer))
                (gossip/lease-tick (gossip/get-id @node) node)
                (drain)))
            (when (= mode :indirect) (refresh nodes settle-rounds))
            {:size size :seed seed :mode mode :leases? leases? :seed-size seed-size
             :weight-rounds weight-rounds :settle-rounds settle-rounds
             :before before :after (when leases? (assoc (view-stats nodes)
                                                       :strongly-connected? (strongly-connected? nodes)))
             :edge-mismatches (edge-mismatches nodes (:nodes net))
             :routing-before routing-before
             :routing-after (when (= mode :indirect) (routing-stats nodes (nodes 0)))}))))))

(defn -main [& [options]]
  (let [{:keys [sizes seeds modes] :or {sizes [100 500] seeds [1 2 3]
                                       modes [:random :single :indirect]} :as options}
        (if options (edn/read-string options) {})]
    (doseq [size sizes seed seeds mode modes]
      (prn (run-scenario (assoc options :size size :seed seed :mode mode)))
      (flush)))
  (shutdown-agents))
