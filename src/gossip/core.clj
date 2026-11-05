(ns gossip.core
  (:require [gossip.wire :as wire]
            [gossip.weights :as weights]
            [udp-wrapper.core :refer [get-ip get-port]]
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
                     lease-duration-ms
                     indirection?]
              :or {in-view []
                   partial-view []
                   heartbeat-counter 5
                   forward-counters {}
                   received-messages #{}
                   c gossip.core/c
                   indirection? false}}]
  (when-not (and (integer? c) (<= 0 c 1000))
    (throw (ex-info "c must be an integer between 0 and 1000" {:c c})))
  (when-not (instance? Boolean indirection?)
    (throw (ex-info "indirection? must be a boolean" {})))
  (when (and (some? lease-duration-ms)
             (not (and (integer? lease-duration-ms) (<= 1 lease-duration-ms 86400000))))
    (throw (ex-info "Lease duration must be 1..86400000 milliseconds" {})))
  (wire/node-id ip port)
  (doseq [id (concat in-view partial-view)] (wire/require-id id))
  {:ip ip
   :port port
   :in-view (vec (distinct (remove #{(wire/node-id ip port)} in-view)))
   :partial-view (vec (distinct (remove #{(wire/node-id ip port)} partial-view)))
   :heartbeat-counter heartbeat-counter
   :forward-counters forward-counters
   :received-messages received-messages
   :c c
   :lease-duration-ms lease-duration-ms
   :indirection? indirection?})

(defn get-id
  "Get the id of a node map e.g. {:ip \"192.168.1.1\" :port 1024} 
  as a string \"ip:port\"."
  [node]
  (wire/node-id (:ip node) (:port node)))

(defn- add-peer [node view peer]
  (if (or (= peer (get-id node)) (some #{peer} (view node)))
    node
    (let [peers (conj (vec (view node)) peer)
          weight-key (if (= view :partial-view) :out-weights :in-weights)]
      (assoc node view peers
                  weight-key (weights/reconcile peers (weight-key node))))))

(defn- remove-peer [node view peer]
  (let [weight-key (if (= view :partial-view) :out-weights :in-weights)]
    (-> node
        (update-in [view] #(vec (remove #{peer} %)))
        (update-in [weight-key] dissoc peer))))

(defn post-send-initial
  "Add contact-id to subscriber's partial-view."
  [subscriber-ref contact-id]
  (wire/require-id contact-id)
  (dosync (alter subscriber-ref add-peer :partial-view contact-id)))

(declare expire-subscriptions! send-subscription forward-prob-accept)

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
            (alter node-ref assoc :subscription subscription
                   :renewal? (some? previous) :renewal-pending? (some? previous))
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
      (when-let [own (:subscription @node-ref)]
        (doseq [[peer lease] (:incoming-leases @node-ref)
                :when (not= (:id lease) (get-id @node-ref))]
          (alter node-ref assoc-in [:incoming-leases peer] own)))
      (doseq [[id lease] (:leases @node-ref)
              :when (<= (:expires-at lease) now)]
        (alter node-ref remove-peer :partial-view id)
        (alter node-ref update-in [:leases] dissoc id))
      (doseq [[id lease] (:incoming-leases @node-ref)
              :when (<= (:expires-at lease) now)]
        (alter node-ref remove-peer :in-view id)
        (alter node-ref update-in [:incoming-leases] dissoc id)))))

(defn- notify-kept
  "Acknowledge an edge using the destination's subscription generation."
  [socket node-ref subscriber-id]
  (if-let [subscription (get-in @node-ref [:leases subscriber-id])]
    (wire/send! socket subscriber-id
                (str "edge-lease--" (wire/encode-subscription subscription)))
    (wire/send! socket subscriber-id "kept")))

(defn- record-incoming! [node-ref peer subscription]
  (dosync
    (alter node-ref add-peer :in-view peer)
    (when subscription
      (alter node-ref assoc-in [:incoming-leases peer] subscription))))

(defn- receive-lease-info [socket node-ref sender subscription]
  (when (and (= sender (:id subscription))
             (remember-subscription! node-ref subscription))
    (let [has-edge? (dosync
                      (when (some #{sender} (:partial-view @node-ref))
                        (alter node-ref assoc-in [:leases sender] subscription)
                        true))]
      (when has-edge? (notify-kept socket node-ref sender)))))

(defn send-initial
  "Send an initial subscription message to a contact node."
  [socket subscriber-ref contact-id]
  (wire/require-id contact-id)
  (let [subscription (own-subscription! subscriber-ref)]
    ;; Prepare local state before sending, because a reply may arrive immediately.
    (post-send-initial subscriber-ref contact-id)
    (dosync
      (alter subscriber-ref update-in [:contacts]
             #(vec (distinct (conj (or % []) contact-id))))
      (when-let [duration (:lease-duration-ms @subscriber-ref)]
        (let [known (get-in @subscriber-ref [:known-subscriptions contact-id])]
          (alter subscriber-ref assoc-in [:leases contact-id]
                 (if (and known (< (now-ms) (:expires-at known)))
                   known
                   {:id contact-id :epoch 0 :expires-at (+ (now-ms) duration)})))))
    (wire/send! socket contact-id
                (if subscription
                  (str (if (:renewal? @subscriber-ref) "renew--" "join--")
                       (wire/encode-subscription subscription))
                  "initial"))))

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
    (if (empty? partial-view)
      ;; Seed a connected group when the first subscriber reaches an empty node.
      (forward-prob-accept socket receiving-node-ref subscriber-id)
      (do
        (run! f partial-view)
        (run! f (repeatedly (get @receiving-node-ref :c c) #(rand-nth partial-view)))))
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
      (when first-join?
        (if (seq view)
          (doseq [peer (concat view (repeatedly extra #(rand-nth view)))]
            (send-subscription socket peer subscription))
          (forward-prob-accept socket node-ref (:id subscription) subscription)))
      (when-not renewal?
        (let [contact-subscription (own-subscription! node-ref)]
          (record-incoming! node-ref (:id subscription) contact-subscription)
          (when contact-subscription
            (wire/send! socket (:id subscription)
                        (str "lease-info--" (wire/encode-subscription contact-subscription))))))
      (wire/send! socket (:id subscription)
                  (str "renewed--" (wire/encode-subscription subscription))))))

(defn refresh-weights
  "Normalize incoming and outgoing arc weights and tell the corresponding peers."
  [socket node-ref]
  (let [node (dosync
               (alter node-ref
                      (fn [node]
                        (assoc node
                               :out-weights (weights/normalize
                                              (weights/reconcile (:partial-view node) (:out-weights node)))
                               :in-weights (weights/normalize
                                             (weights/reconcile (:in-view node) (:in-weights node)))))))]
    (doseq [[peer weight] (:out-weights node)]
      (wire/send! socket peer (str "weight--out|" weight)))
    (doseq [[peer weight] (:in-weights node)]
      (wire/send! socket peer (str "weight--in|" weight)))))

(defn schedule-weight-refresh
  "Schedule the asynchronous arc-weight updates used by subscription indirection."
  [socket node-ref interval]
  (every interval #(refresh-weights socket node-ref) my-pool))

(defn- receive-weight [node-ref sender direction weight]
  (dosync
    (let [[view key] (if (= direction "out")
                       [:in-view :in-weights]
                       [:partial-view :out-weights])]
      (when (some #{sender} (view @node-ref))
        (alter node-ref assoc-in [key sender] weight)))))

(defn- handle-walk [socket node-ref walk]
  (expire-subscriptions! node-ref)
  (when (or (nil? (:subscription walk))
            (remember-subscription! node-ref (:subscription walk)))
    (let [node @node-ref
          view (:partial-view node)
          peer (when (and (or (pos? (:hops walk)) (= (:id walk) (get-id node))) (seq view))
                 (weights/choose-peer view (:out-weights node) (rand)))]
      (if peer
        (wire/send! socket peer
                    (str "walk--" (wire/encode-walk (update-in walk [:hops] #(max 0 (dec %))))))
        ;; A node cannot serve as its own subscription contact.
        (when (not= (:id walk) (get-id node))
          (when-not (:renewal? walk)
            (wire/send! socket (:id walk) (str "contact--" (:contact walk))))
          (if-let [subscription (:subscription walk)]
            (handle-join socket node-ref subscription (:renewal? walk))
            (handle-receive-initial socket node-ref (:id walk))))))))

(defn- route-join [socket node-ref subscriber-id subscription renewal?]
  (if (:indirection? @node-ref)
    (handle-walk socket node-ref
                 {:id subscriber-id :subscription subscription
                  :contact (get-id @node-ref)
                  :hops (min 20000 (* 2 (count (:partial-view @node-ref))))
                  :renewal? renewal?})
    (if subscription
      (handle-join socket node-ref subscription renewal?)
      (handle-receive-initial socket node-ref subscriber-id))))

(defn- receive-contact [socket node-ref old-contact new-contact]
  (when (not= new-contact (get-id @node-ref))
    (let [changed? (dosync
                     (when (some #{old-contact} (:partial-view @node-ref))
                       (alter node-ref remove-peer :partial-view old-contact)
                       (alter node-ref add-peer :partial-view new-contact)
                       (when-let [lease (get-in @node-ref [:leases old-contact])]
                         (alter node-ref update-in [:leases] dissoc old-contact)
                         (alter node-ref assoc-in [:leases new-contact] (assoc lease :id new-contact)))
                       true))]
      (when changed?
        (when (not= old-contact new-contact)
          (wire/send! socket old-contact "forget"))
        (notify-kept socket node-ref new-contact)))))

(defn lease-tick
  "Expire dead peers and renew, retrying lost requests until acknowledged.
  A node whose entire view expires rejoins through a remembered peer."
  [socket node-ref]
  (expire-subscriptions! node-ref)
  (let [node @node-ref
        previous (:subscription node)
        contacts (vec (distinct (concat (:partial-view node) (:contacts node)
                                        (keys (:known-subscriptions node)))))
        due? (or (nil? previous) (<= (:expires-at previous) (now-ms)))]
    (when (and (:lease-duration-ms node) (seq contacts)
               (or due? (:renewal-pending? node) (empty? (:partial-view node))))
      (let [contact (rand-nth contacts)
            subscription (own-subscription! node-ref)]
        (if (empty? (:partial-view @node-ref))
          (do
            (dosync (alter node-ref assoc :renewal? false))
            (send-initial socket node-ref contact))
          (do
            (dosync (alter node-ref assoc :renewal-pending? true))
            (wire/send! socket contact
                        (str "renew--" (wire/encode-subscription subscription)))))))))

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
        "walk" (when-let [walk (wire/decode-walk payload)]
                 (handle-walk socket receiving-node-ref walk))
        "weight" (when-let [[direction weight] (wire/decode-weight payload)]
                   (receive-weight receiving-node-ref sender direction weight))
        "contact" (when (wire/parse-id payload)
                    (receive-contact socket receiving-node-ref payload sender))
        "join" (when-let [subscription (wire/decode-subscription payload)]
                 (when (= sender (:id subscription))
                   (route-join socket receiving-node-ref sender subscription false)))
        "renew" (when-let [subscription (wire/decode-subscription payload)]
                  (when (= sender (:id subscription))
                    (route-join socket receiving-node-ref sender subscription true)))
        "renewed" (when-let [subscription (wire/decode-subscription payload)]
                    (dosync
                      (when (= subscription (:subscription @receiving-node-ref))
                        (alter receiving-node-ref assoc :renewal-pending? false))))
        "subscription" (when-let [subscription (wire/decode-subscription payload)]
                         (handle-subscription socket receiving-node-ref subscription))
        "lease-info" (when-let [subscription (wire/decode-subscription payload)]
                       (receive-lease-info socket receiving-node-ref sender subscription))
        "edge-lease" (when-let [subscription (wire/decode-subscription payload)]
                       (expire-subscriptions! receiving-node-ref)
                       (when (and (= subscription (:subscription @receiving-node-ref))
                                  (< (now-ms) (:expires-at subscription)))
                         (record-incoming! receiving-node-ref sender subscription)))
        ;; Accept notifications from the earlier lease protocol, then correct
        ;; the sender's edge deadline with this destination's own subscription.
        "kept-lease" (when-let [subscription (wire/decode-subscription payload)]
                       (when (and (= sender (:id subscription))
                                  (remember-subscription! receiving-node-ref subscription))
                         (expire-subscriptions! receiving-node-ref)
                         (let [own (own-subscription! receiving-node-ref)]
                           (record-incoming! receiving-node-ref sender own)
                           (when own
                             (wire/send! socket sender
                                         (str "lease-info--" (wire/encode-subscription own)))))))
        "initial" (when (= 1 (count fields))
                    (route-join socket receiving-node-ref sender nil false))
        "forward" (when (wire/parse-id payload)
                    (handle-receive-forward socket receiving-node-ref payload))
        "kept" (when (= 1 (count fields))
                 (dosync (alter receiving-node-ref add-peer :in-view sender)))
        "forget" (when (= 1 (count fields))
                   (dosync
                     (alter receiving-node-ref remove-peer :in-view sender)
                     (alter receiving-node-ref update-in [:incoming-leases] dissoc sender)))
        "heartbeat" (when (= 1 (count fields))
                      (handle-receive-heartbeat receiving-node-ref))
        "remove" (when (= 1 (count fields))
                   (handle-receive-remove receiving-node-ref sender))
        "replace" (when (wire/parse-id payload)
                    (handle-receive-replace socket receiving-node-ref sender payload))
        "gossip" (when (some? payload)
                   (handle-receive-gossip socket receiving-node-ref payload))
        nil))))
