(ns gossip.utils-test
  (:require [clojure.test :refer :all]
            [gossip.utils :as utils]
            [udp-wrapper.core :as udp]))

(definterface SocketState (isClosed []))

(deftest receiver-survives-errors-and-restores-buffer-capacity
  (let [closed? (atom false)
        socket (reify SocketState (isClosed [_] @closed?))
        calls (atom 0)
        lengths (atom [])
        errors (atom [])
        packet (udp/empty-packet 128)]
    (with-redefs [udp/receive-message
                    (fn [_ pkt]
                      (swap! lengths conj (.getLength pkt))
                      (.setLength pkt 1)
                      (swap! calls inc))]
        (let [receiver (utils/receive socket packet nil
                         (fn [& _]
                           (if (= 1 @calls)
                             (throw (IllegalArgumentException. "bad packet"))
                             (reset! closed? true)))
                         {:on-error #(swap! errors conj %)})]
          (is (nil? (deref receiver 2000 ::timeout)))
          (is (= 1 (count @errors)))
          (is (= [128 128] @lengths))))))
