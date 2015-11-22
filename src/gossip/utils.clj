(ns gossip.utils
  (:require [udp-wrapper.core :refer [receive-message]]))

(defn receive
  "Receive UDP message in a loop."
  [socket pkt node handler]
  (future (while true (do (receive-message socket pkt) (handler socket node pkt)))))
