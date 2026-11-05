(ns gossip.weights)

(defn reconcile
  "Keep weights for current peers and initialize new edges to the existing mean."
  [peers weights]
  (let [existing (keep #(get weights %) peers)
        mean (if (seq existing) (/ (reduce + existing) (count existing)) 1.0)]
    (into {} (map (fn [peer] [peer (get weights peer mean)]) peers))))

(defn normalize [weights]
  (let [total (reduce + 0.0 (vals weights))]
    (into {} (map (fn [[peer weight]]
                    [peer (if (pos? total) (/ weight total) (/ 1.0 (count weights)))])
                  weights))))

(defn choose-peer
  "Choose a current outgoing edge in proportion to its weight."
  [peers weights sample]
  (let [weights (normalize (reconcile peers weights))]
    (loop [[peer & remaining] peers
           cumulative 0.0]
      (when peer
        (let [next-total (+ cumulative (weights peer))]
          (if (or (< sample next-total) (empty? remaining))
            peer
            (recur remaining next-total)))))))
