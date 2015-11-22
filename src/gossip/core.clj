(ns gossip.core
  (:require [clojure.string :refer [split]]
            [udp-wrapper.core :refer [send-message packet make-address get-ip
                                      get-port get-data get-bytes-utf8]]
            [overtone.at-at :refer [mk-pool every]])
  (:import (java.util Random)))

(def my-pool (mk-pool))
(def c 10)

(defn create-node
  "Create a node that will be part of the Gossip network."
  [ip port & {:keys [in-view
                     partial-view
                     heartbeat-counter
                     forward-counters
                     received-messages]
              :or {in-view []
                   partial-view []
                   heartbeat-counter 5
                   forward-counters {}
                   received-messages #{}}}]
  {:ip ip
   :port port
   :in-view in-view
   :partial-view partial-view
   :heartbeat-counter heartbeat-counter
   :forward-counters forward-counters
   :received-messages received-messages})

(defn get-id
  "Get the id of a node map e.g. {:ip \"192.168.1.1\" :port 1024} 
  as a string \"ip:port\"."
  [node]
  (str (:ip node) ":" (:port node)))

(defn post-send-initial
  "Add contact-id to subscriber's partial-view."
  [subscriber-ref contact-id]
  (dosync (ref-set subscriber-ref (update-in
                                   @subscriber-ref
                                   [:partial-view]
                                   conj
                                   contact-id))))

(defn send-initial
  "Send an initial subscription message to a contact node."
  [socket subscriber-ref contact-id]
  (let [contact-info (split contact-id #":")]
    (send-message socket (packet
                          (get-bytes-utf8 "initial")
                          (make-address (first contact-info))
                          (Integer/parseInt (last contact-info))))
    (post-send-initial subscriber-ref contact-id)))

(defn forward
  "Send a ForwardSubscription message containing the subscriber-id to a node."
  [socket subscriber-id node-id]
  (let [node-info (split node-id #":")]
    (send-message socket (packet
                          (get-bytes-utf8 (str "forward" "--" subscriber-id))
                          (make-address (first node-info))
                          (Integer/parseInt (last node-info))))))

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
      (run! f (repeatedly c #(rand-nth partial-view))))
    (dosync (ref-set receiving-node-ref (update-in
                                         @receiving-node-ref
                                         [:in-view]
                                         conj
                                         subscriber-id)))))

(defn forward-prob-accept
  "Accept a ForwardSubscription request with probablity 
  (p = 1 / 1+ size of partial-view) or forward it to a
  random node in the partial-view and update the 
  forward-counters map."
  [socket forwarded-to-ref subscriber-id]
  (let [node forwarded-to-ref]
    (dosync (if (<= (.nextDouble (Random.)) (/ 1 (inc (count (:partial-view @node)))))
              (ref-set node (update-in
                             @node
                             [:partial-view]
                             conj
                             subscriber-id))
              (do
                (when-not empty? (:partial-view @node)
                          (forward
                           socket
                           subscriber-id
                           (rand-nth (:partial-view @node))))
                (if (contains? (:forward-counters @node) (keyword subscriber-id))
                  (ref-set node (update-in
                                 @node
                                 [:forward-counters (keyword subscriber-id)]
                                 inc))
                  (ref-set node (update-in
                                 @node
                                 [:forward-counters]
                                 assoc
                                 (keyword subscriber-id)
                                 0))))))))

(defn handle-receive-forward
  "Handle a ForwardSubscription request, if the request has been received more than
  10 times, simply discard the thread."
  [socket forwarded-to-ref subscriber-id]
  (if (contains? (:forward-counters @forwarded-to-ref) (keyword subscriber-id))
    (when (< (inc ((keyword subscriber-id) (:forward-counters @forwarded-to-ref))) 10)
      (forward-prob-accept socket forwarded-to-ref subscriber-id))
    (forward-prob-accept socket forwarded-to-ref subscriber-id)))

(defn send-heartbeat
  ""
  [socket node-id]
  (let [node-info (split node-id #":")]
    (send-message socket (packet
                          (get-bytes-utf8 "heartbeat")
                          (make-address (first node-info))
                          (Integer/parseInt (last node-info))))))

(defn schedule-heartbeat-send
  "Send a heartbeat to all nodes in the partial-view after every \"interval\"."
  [socket sender-ref interval]
  (every
   interval
   #(run! (partial send-heartbeat socket) (:partial-view @sender-ref))
   my-pool))

(defn schedule-heartbeat-dec
  "Decrease the value of the heartbeat-counter after every \"interval\",
  if the counter reaches zero, send an InitialSubscription request to
  a random node from the partial-view."
  [socket node-ref interval]
  (every
   interval
   #(dosync (if (<= (dec (:heartbeat-counter @node-ref)) 0)
              (when-not (empty? (:partial-view @node-ref))
                (send-initial socket node-ref (rand-nth (:partial-view @node-ref))))
              (ref-set node-ref (update-in
                                 @node-ref
                                 [:heartbeat-counter]
                                 dec))))
   my-pool))

(defn handle-receive-heartbeat
  "Update the heartbeat-counter."
  [receiving-node-ref]
  (dosync (ref-set receiving-node-ref (assoc
                                       @receiving-node-ref
                                       :heartbeat-counter
                                       10))))

(defn send-remove
  ""
  [socket node-id]
  (let [node-info (split node-id #":")]
    (send-message socket (packet
                          (get-bytes-utf8 "remove")
                          (make-address (first node-info))
                          (Integer/parseInt (last node-info))))))

(defn handle-receive-remove
  ""
  [receiving-node-ref to-remove-id]
  (dosync (ref-set receiving-node-ref (assoc
                                       @receiving-node-ref
                                       :partial-view
                                       (vec (remove
                                             #{to-remove-id}
                                             (:partial-view @receiving-node-ref)))))))

(defn send-replace
  ""
  [socket node-id surrogate-id]
  (let [node-info (split node-id #":")]
    (send-message socket (packet
                          (get-bytes-utf8 (str "replace" "--" surrogate-id))
                          (make-address (first node-info))
                          (Integer/parseInt (last node-info))))))

(defn handle-receive-replace
  ""
  [receiving-node-ref original-id surrogate-id]
  (dosync (ref-set
           receiving-node-ref
           (assoc
            @receiving-node-ref
            :partial-view
            (-> (remove #{original-id} (:partial-view @receiving-node-ref))
                (conj surrogate-id)
                vec)))))

(defn unsubscribe
  ""
  [socket unsubscriber-ref]
  (let [in-view (:in-view @unsubscriber-ref)
        partial-view (:partial-view @unsubscriber-ref)
        in-view-count (count in-view)
        partial-view-count (count partial-view)]
    (when-not (and (empty? in-view) (empty? partial-view))
      (doseq [x (range 0 (if (< in-view-count 15)
                           in-view-count
                           (- in-view-count c 1)))]
        (send-replace
         socket
         (in-view x)
         (partial-view (mod x partial-view-count))))
      (when-not (< in-view-count 15)
        (doseq [y (subvec in-view (- in-view-count c))]
          (send-remove socket y))))))

(defn gossip
  "Send a message to all nodes in the partial-view."
  [socket gossiper-ref message]
  (doseq [x (:partial-view @gossiper-ref)]
    (send-message socket (packet
                          (get-bytes-utf8 (str "gossip" "--" message))
                          (make-address (first (split x #":")))
                          (Integer/parseInt (last (split x #":")))))))

(defn handle-receive-gossip
  "If the node has received the message before, don't do anything.
  If it's the first time that the node has received the message,
  add the message to `received-messages' and gossip it."
  [socket receiving-node-ref message]
  (dosync (ref-set receiving-node-ref (update-in
                                       @receiving-node-ref
                                       [:received-messages]
                                       conj
                                       message)))
  (gossip socket receiving-node-ref message))

(defn handle-message
  "Handle received messages."
  [socket receiving-node-ref pkt]
  (let [data (split (get-data pkt) #"--")
        ip (get-ip pkt)
        port (get-port pkt)]
    (case (first data)
      "initial" (handle-receive-initial
                 socket
                 receiving-node-ref
                 (get-id {:ip ip :port port}))
      "forward" (handle-receive-forward
                 socket
                 receiving-node-ref
                 (last data))
      "heartbeat" (handle-receive-heartbeat receiving-node-ref)
      "remove" (handle-receive-remove
                receiving-node-ref
                (get-id {:ip ip :port port}))
      "replace" (handle-receive-replace
                 receiving-node-ref
                 (get-id {:ip ip :port port})
                 (last data))
      "gossip" (handle-receive-gossip socket receiving-node-ref (last data)))))
