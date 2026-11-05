(ns gossip.utils
  (:require [udp-wrapper.core :refer [receive-message]])
  (:import (java.net SocketException SocketTimeoutException)))

(defn- report-error [error]
  (binding [*out* *err*]
    (println "Gossip receiver:" (.getMessage error))))

(defn receive
  "Receive datagrams until the socket closes. Report handler errors and continue.
  The packet buffer must fit the largest supported message, up to 65507 bytes."
  [socket pkt node handler & [{:keys [on-error] :or {on-error report-error}}]]
  (let [capacity (alength (.getData pkt))]
    (future
      (while (and (not (.isClosed socket))
                  (not (.isInterrupted (Thread/currentThread))))
        (try
          ;; DatagramSocket.receive changes the packet length after every read.
          (.setLength pkt capacity)
          (receive-message socket pkt)
          (handler socket node pkt)
          (catch SocketTimeoutException _ nil)
          (catch SocketException error
            (when-not (.isClosed socket) (on-error error)))
          (catch Exception error (on-error error)))))))
