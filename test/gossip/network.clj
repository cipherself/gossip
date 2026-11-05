(ns gossip.network
  (:require [gossip.core :as gossip]
            [udp-wrapper.core :as udp]))

(defn network
  "An explicit delivery queue with real packet framing and optional packet loss."
  [nodes]
  {:nodes (into {} (map (fn [node] [(gossip/get-id @node) node]) nodes))
   :pending (atom clojure.lang.PersistentQueue/EMPTY)
   :sent (atom [])
   :drop? (atom (constantly false))})

(defn send-packet [network sender packet]
  (let [destination (str (udp/get-ip packet) ":" (udp/get-port packet))
        event {:sender sender :destination destination :text (udp/get-data packet)}]
    (swap! (:sent network) conj event)
    (when-not (@(:drop? network) event)
      (swap! (:pending network) conj event))))

(defn drain
  "Deliver at most limit packets, preventing faulty protocols from hanging tests."
  ([network] (drain network 1000))
  ([network limit]
   (loop [remaining limit]
     (when-let [{:keys [sender destination text]} (peek @(:pending network))]
       (when (zero? remaining) (throw (ex-info "Delivery limit exceeded" {})))
       (swap! (:pending network) pop)
       (when-let [node ((:nodes network) destination)]
         (let [[host port] (gossip.wire/require-id sender)]
           (gossip/handle-message destination node
             (udp/packet (udp/get-bytes-utf8 text) (udp/make-address host) port))))
       (recur (dec remaining))))))
