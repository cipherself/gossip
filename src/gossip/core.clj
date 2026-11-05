(ns gossip.core
  (:require [gossip.wire :as wire]
            [udp-wrapper.core :refer [send-message packet make-address get-ip
                                      get-port get-data get-bytes-utf8]]
            [overtone.at-at :refer [mk-pool every]]))

(def my-pool (mk-pool))
(def c 10)

(defn create-node
  "Create a node that will be part of the Gossip network."
  [ip port & {:keys [in-view
                     partial-view
                     heartbeat-counter
                     forward-counters
                     received-messages
                     c]
              :or {in-view []
                   partial-view []
                   heartbeat-counter 5
                   forward-counters {}
                   received-messages #{}
                   c gossip.core/c}}]
  (when-not (and (integer? c) (<= 0 c 1000))
    (throw (ex-info "c must be an integer between 0 and 1000" {:c c})))
  (wire/node-id ip port)
  (doseq [id (concat in-view partial-view)] (wire/require-id id))
  {:ip ip
   :port port
   :in-view in-view
   :partial-view partial-view
   :heartbeat-counter heartbeat-counter
   :forward-counters forward-counters
   :received-messages received-messages
   :c c})

(defn get-id
  "Get the id of a node map e.g. {:ip \"192.168.1.1\" :port 1024} 
  as a string \"ip:port\"."
  [node]
  (wire/node-id (:ip node) (:port node)))

(defn- add-peer [node view peer]
  (if (or (= peer (get-id node)) (some #{peer} (view node)))
    node
    (update-in node [view] conj peer)))

(defn- remove-peer [node view peer]
  (update-in node [view] #(vec (remove #{peer} %))))

(defn post-send-initial
  "Add contact-id to subscriber's partial-view."
  [subscriber-ref contact-id]
  (wire/require-id contact-id)
  (dosync (alter subscriber-ref add-peer :partial-view contact-id)))

(defn send-initial
  "Send an initial subscription message to a contact node."
  [socket subscriber-ref contact-id]
  (wire/send! socket contact-id "initial")
  (post-send-initial subscriber-ref contact-id))

(defn forward
  "Send a ForwardSubscription message containing the subscriber-id to a node."
  [socket subscriber-id node-id]
  (wire/require-id subscriber-id)
  (wire/send! socket node-id (str "forward--" subscriber-id)))

(defn handle-receive-initial
  "Forward subscriber-id to all nodes in receiving-node's partial-view.
  Forward `c' additional copies of subscriber-id 
  to random nodes in receiving-node's partial-view.
  Add subscriber id to receiving-node's in-view."
  [socket receiving-node-ref subscriber-id]
  (let [f (partial forward socket subscriber-id)
        partial-view (:partial-view @receiving-node-ref)]
    (when-not (empty? partial-view)
      (run! f partial-view)
      (run! f (repeatedly (get @receiving-node-ref :c c) #(rand-nth partial-view))))
    (dosync (alter receiving-node-ref add-peer :in-view subscriber-id))))

(defn forward-prob-accept
  "Keep a subscription or forward it, processing at most ten copies per subscriber."
  [socket node-ref subscriber-id]
  (wire/require-id subscriber-id)
  (let [sample (rand)
        action (dosync
                 (let [node @node-ref
                       view (:partial-view node)
                       counter (get-in node [:forward-counters (keyword subscriber-id)] -1)]
                   (when (< counter 9)
                     (alter node-ref assoc-in [:forward-counters (keyword subscriber-id)] (inc counter))
                     (if (and (not= subscriber-id (get-id node))
                              (not (some #{subscriber-id} view))
                              (<= sample (/ 1 (inc (count view)))))
                       (do (alter node-ref add-peer :partial-view subscriber-id)
                           [:kept subscriber-id])
                       (when (seq view) [:forward (rand-nth view)])))))]
    (case (first action)
      :kept (wire/send! socket subscriber-id "kept")
      :forward (forward socket subscriber-id (second action))
      nil)))

(defn handle-receive-forward
  "Handle a forwarded subscription, including duplicate and self subscriptions."
  [socket node-ref subscriber-id]
  (forward-prob-accept socket node-ref subscriber-id))

(defn send-heartbeat
  [socket node-id]
  (wire/send! socket node-id "heartbeat"))

(defn schedule-heartbeat-send
  "Send a heartbeat to all nodes in the partial-view after every \"interval\"."
  [socket sender-ref interval]
  (every
   interval
   #(run! (partial send-heartbeat socket) (:partial-view @sender-ref))
   my-pool))

(defn heartbeat-tick
  "Decrease the isolation counter and retry subscription when it expires."
  [socket node-ref]
  (let [contact (dosync
                  (let [node @node-ref
                        counter (dec (:heartbeat-counter node))]
                    (alter node-ref assoc :heartbeat-counter (if (pos? counter) counter 10))
                    (when (and (not (pos? counter)) (seq (:partial-view node)))
                      (rand-nth (:partial-view node)))))]
    (when contact (send-initial socket node-ref contact))))

(defn schedule-heartbeat-dec
  "Periodically detect isolation and retry subscription through a known peer."
  [socket node-ref interval]
  (every interval #(heartbeat-tick socket node-ref) my-pool))

(defn handle-receive-heartbeat
  "Update the heartbeat-counter."
  [receiving-node-ref]
  (dosync (ref-set receiving-node-ref (assoc
                                       @receiving-node-ref
                                       :heartbeat-counter
                                       10))))

(defn send-remove
  [socket node-id]
  (wire/send! socket node-id "remove"))

(defn handle-receive-remove
  ""
  [receiving-node-ref to-remove-id]
  (dosync (alter receiving-node-ref remove-peer :partial-view to-remove-id)))

(defn send-replace
  [socket node-id surrogate-id]
  (wire/require-id surrogate-id)
  (wire/send! socket node-id (str "replace--" surrogate-id)))

(defn handle-receive-replace
  "Replace an existing edge once, rejecting duplicate and self edges."
  ([node-ref original-id surrogate-id]
   (wire/require-id surrogate-id)
   (dosync
     (when (some #{original-id} (:partial-view @node-ref))
       (alter node-ref remove-peer :partial-view original-id)
       (let [before (:partial-view @node-ref)]
         (alter node-ref add-peer :partial-view surrogate-id)
         (not= before (:partial-view @node-ref))))))
  ([socket node-ref original-id surrogate-id]
   (when (handle-receive-replace node-ref original-id surrogate-id)
     (wire/send! socket surrogate-id "kept"))))

(defn unsubscribe
  "Replace incoming edges except for c+1 removals, then detach outgoing edges."
  [socket node-ref]
  (let [node @node-ref
        incoming (vec (distinct (:in-view node)))
        outgoing (vec (distinct (:partial-view node)))
        replacements (if (seq outgoing)
                       (max 0 (- (count incoming) (inc (get node :c c))))
                       0)]
    (doseq [[index peer] (map-indexed vector incoming)]
      (if (< index replacements)
        (send-replace socket peer (outgoing (mod index (count outgoing))))
        (send-remove socket peer)))
    (doseq [peer outgoing]
      (wire/send! socket peer "forget"))
    (dosync (alter node-ref assoc :partial-view [] :in-view [] :forward-counters {}))))

(defn gossip
  "Send a message to all nodes in the partial-view."
  [socket gossiper-ref message]
  (when-not (string? message)
    (throw (ex-info "Gossip payload must be a string" {})))
  (doseq [id (:partial-view @gossiper-ref)]
    (wire/send! socket id (str "gossip--" message))))

(defn handle-receive-gossip
  "If the node has received the message before, don't do anything.
  If it's the first time that the node has received the message,
  add the message to `received-messages' and gossip it."
  [socket receiving-node-ref message]
  (let [first-receipt? (dosync
                         (when-not (contains? (:received-messages @receiving-node-ref) message)
                           (alter receiving-node-ref update-in [:received-messages] conj message)
                           true))]
    (when first-receipt?
      (gossip socket receiving-node-ref message))))

(defn handle-message
  "Handle received messages."
  [socket receiving-node-ref pkt]
  (when-let [[command payload :as fields] (wire/decode pkt)]
    (let [sender (get-id {:ip (get-ip pkt) :port (get-port pkt)})]
      (case command
        "initial" (when (= 1 (count fields))
                    (handle-receive-initial socket receiving-node-ref sender))
        "forward" (when (wire/parse-id payload)
                    (handle-receive-forward socket receiving-node-ref payload))
        "kept" (when (= 1 (count fields))
                 (dosync (alter receiving-node-ref add-peer :in-view sender)))
        "forget" (when (= 1 (count fields))
                   (dosync (alter receiving-node-ref remove-peer :in-view sender)))
        "heartbeat" (when (= 1 (count fields))
                      (handle-receive-heartbeat receiving-node-ref))
        "remove" (when (= 1 (count fields))
                   (handle-receive-remove receiving-node-ref sender))
        "replace" (when (wire/parse-id payload)
                    (handle-receive-replace socket receiving-node-ref sender payload))
        "gossip" (when (some? payload)
                   (handle-receive-gossip socket receiving-node-ref payload))
        nil))))
