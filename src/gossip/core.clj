(ns gossip.core
  (:require [gossip.wire :as wire]
            [udp-wrapper.core :refer [send-message packet make-address get-ip
                                      get-port get-data get-bytes-utf8]]
            [overtone.at-at :refer [mk-pool every]]))

(def my-pool (mk-pool))
(def c 10)

(defn now-ms [] (System/currentTimeMillis))

(defn create-node
  "Create a node that will be part of the Gossip network."
  [ip port & {:keys [in-view
                     partial-view
                     heartbeat-counter
                     forward-counters
                     received-messages
                     c
                     lease-duration-ms]
              :or {in-view []
                   partial-view []
                   heartbeat-counter 5
                   forward-counters {}
                   received-messages #{}
                   c gossip.core/c}}]
  (when-not (and (integer? c) (<= 0 c 1000))
    (throw (ex-info "c must be an integer between 0 and 1000" {:c c})))
  (when (and lease-duration-ms
             (not (and (integer? lease-duration-ms) (<= 1 lease-duration-ms 86400000))))
    (throw (ex-info "Lease duration must be 1..86400000 milliseconds" {})))
  (wire/node-id ip port)
  (doseq [id (concat in-view partial-view)] (wire/require-id id))
  {:ip ip
   :port port
   :in-view in-view
   :partial-view partial-view
   :heartbeat-counter heartbeat-counter
   :forward-counters forward-counters
   :received-messages received-messages
   :c c
   :lease-duration-ms lease-duration-ms})

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

(declare expire-subscriptions! send-subscription)

(defn- own-subscription!
  "Create a generation once per lease, reusing it for retry attempts."
  [node-ref]
  (dosync
    (let [node @node-ref
          now (now-ms)
          previous (:subscription node)]
      (when-let [duration (:lease-duration-ms node)]
        (if (and previous (< now (:expires-at previous)))
          previous
          (let [epoch (max now (inc (get previous :epoch -1)))
                subscription {:id (get-id node) :epoch epoch :expires-at (+ now duration)}]
            (alter node-ref assoc :subscription subscription :renewal? (some? previous))
            subscription))))))

(defn- remember-subscription! [node-ref subscription]
  (let [{:keys [id epoch expires-at]} subscription
        now (now-ms)]
    (when (and (< now expires-at) (<= expires-at (+ now 86400000)))
      (dosync
        (let [previous (get-in @node-ref [:known-subscriptions id])]
          (when (or (nil? previous)
                    (> epoch (:epoch previous))
                    (= subscription previous))
            (when (not= subscription previous)
              (alter node-ref assoc-in [:known-subscriptions id] subscription)
              (alter node-ref update-in [:forward-counters] dissoc (keyword id)))
            true))))))

(defn expire-subscriptions!
  "Remove expired incoming and outgoing edges using the local clock."
  [node-ref]
  (let [now (now-ms)]
    (dosync
      (doseq [[id lease] (:leases @node-ref)
              :when (<= (:expires-at lease) now)]
        (alter node-ref remove-peer :partial-view id)
        (alter node-ref update-in [:leases] dissoc id))
      (doseq [[id lease] (:incoming-leases @node-ref)
              :when (<= (:expires-at lease) now)]
        (alter node-ref remove-peer :in-view id)
        (alter node-ref update-in [:incoming-leases] dissoc id)))))

(defn- notify-kept [socket node-ref subscriber-id]
  (if-let [subscription (own-subscription! node-ref)]
    (wire/send! socket subscriber-id (str "kept-lease--" (wire/encode-subscription subscription)))
    (wire/send! socket subscriber-id "kept")))

(defn send-initial
  "Send an initial subscription message to a contact node."
  [socket subscriber-ref contact-id]
  (wire/require-id contact-id)
  (if-let [subscription (own-subscription! subscriber-ref)]
    (wire/send! socket contact-id (str (if (:renewal? @subscriber-ref) "renew--" "join--")
                                           (wire/encode-subscription subscription)))
    (wire/send! socket contact-id "initial"))
  (post-send-initial subscriber-ref contact-id)
  (dosync (alter subscriber-ref update-in [:contacts]
                 #(vec (distinct (conj (or % []) contact-id)))))
  (when-let [duration (:lease-duration-ms @subscriber-ref)]
    (dosync
      (alter subscriber-ref assoc-in [:leases contact-id]
             (or (get-in @subscriber-ref [:known-subscriptions contact-id])
                 {:id contact-id :epoch 0 :expires-at (+ (now-ms) duration)})))))

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
  [socket node-ref subscriber-id & [subscription]]
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
                           (when subscription
                             (alter node-ref assoc-in [:leases subscriber-id] subscription))
                           [:kept subscriber-id])
                       (when (seq view) [:forward (rand-nth view)])))))]
    (case (first action)
      :kept (notify-kept socket node-ref subscriber-id)
      :forward (if subscription
                 (send-subscription socket (second action) subscription)
                 (forward socket subscriber-id (second action)))
      nil)))

(defn handle-receive-forward
  "Handle a forwarded subscription, including duplicate and self subscriptions."
  [socket node-ref subscriber-id]
  (forward-prob-accept socket node-ref subscriber-id))

(defn send-subscription [socket peer subscription]
  (wire/send! socket peer (str "subscription--" (wire/encode-subscription subscription))))

(defn- handle-subscription [socket node-ref subscription]
  (expire-subscriptions! node-ref)
  (when (remember-subscription! node-ref subscription)
    (forward-prob-accept socket node-ref (:id subscription) subscription)))

(defn- handle-join [socket node-ref subscription renewal?]
  (expire-subscriptions! node-ref)
  (when (remember-subscription! node-ref subscription)
    (let [first-join? (dosync
                       (when (not= subscription (get-in @node-ref [:processed-joins (:id subscription)]))
                         (alter node-ref assoc-in [:processed-joins (:id subscription)] subscription)
                         true))
          node @node-ref
          view (:partial-view node)
          extra (if renewal? 0 (get node :c c))]
      (when (and first-join? (seq view))
        (doseq [peer (concat view (repeatedly extra #(rand-nth view)))]
          (send-subscription socket peer subscription)))
      (when-not renewal?
        (dosync
          (alter node-ref add-peer :in-view (:id subscription))
          (alter node-ref assoc-in [:incoming-leases (:id subscription)] subscription)))
      (when renewal?
        (wire/send! socket (:id subscription)
                    (str "renewed--" (wire/encode-subscription subscription)))))))

(defn lease-tick
  "Expire dead peers and renew, retrying lost renewal requests until acknowledged."
  [socket node-ref]
  (expire-subscriptions! node-ref)
  (let [node @node-ref
        previous (:subscription node)
        contacts (vec (distinct (concat (:partial-view node) (:contacts node))))
        due? (or (nil? previous) (<= (:expires-at previous) (now-ms)))]
    (when (and (:lease-duration-ms node) (seq contacts)
               (or due? (:renewal-pending? node)))
      (let [subscription (own-subscription! node-ref)]
        (dosync (alter node-ref assoc :renewal-pending? true))
        (wire/send! socket (rand-nth contacts)
                    (str "renew--" (wire/encode-subscription subscription)))))))

(defn schedule-lease-maintenance
  "Run lease expiry and renewal. The interval should be shorter than the lease."
  [socket node-ref interval]
  (every interval #(lease-tick socket node-ref) my-pool))

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
  (dosync
    (alter receiving-node-ref remove-peer :partial-view to-remove-id)
    (alter receiving-node-ref update-in [:leases] dissoc to-remove-id)))

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
       (alter node-ref update-in [:leases] dissoc original-id)
       (let [before (:partial-view @node-ref)]
         (alter node-ref add-peer :partial-view surrogate-id)
         (when-let [duration (:lease-duration-ms @node-ref)]
           (alter node-ref assoc-in [:leases surrogate-id]
                  (or (get-in @node-ref [:known-subscriptions surrogate-id])
                      {:id surrogate-id :epoch 0 :expires-at (+ (now-ms) duration)})))
         (not= before (:partial-view @node-ref))))))
  ([socket node-ref original-id surrogate-id]
   (when (handle-receive-replace node-ref original-id surrogate-id)
     (notify-kept socket node-ref surrogate-id))))

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
    (dosync (alter node-ref assoc :partial-view [] :in-view [] :forward-counters {}
                                :leases {} :incoming-leases {} :contacts []
                                :subscription nil :renewal-pending? false))))

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
        "join" (when-let [subscription (wire/decode-subscription payload)]
                 (when (= sender (:id subscription))
                   (handle-join socket receiving-node-ref subscription false)))
        "renew" (when-let [subscription (wire/decode-subscription payload)]
                  (when (= sender (:id subscription))
                    (handle-join socket receiving-node-ref subscription true)))
        "renewed" (when-let [subscription (wire/decode-subscription payload)]
                    (dosync
                      (when (and (= subscription (:subscription @receiving-node-ref))
                                 (some #{sender} (concat (:partial-view @receiving-node-ref)
                                                        (:contacts @receiving-node-ref))))
                        (alter receiving-node-ref assoc :renewal-pending? false))))
        "subscription" (when-let [subscription (wire/decode-subscription payload)]
                         (handle-subscription socket receiving-node-ref subscription))
        "kept-lease" (when-let [subscription (wire/decode-subscription payload)]
                       (when (and (= sender (:id subscription))
                                  (remember-subscription! receiving-node-ref subscription))
                         (expire-subscriptions! receiving-node-ref)
                         (dosync
                           (alter receiving-node-ref add-peer :in-view sender)
                           (alter receiving-node-ref assoc-in [:incoming-leases sender] subscription))))
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
