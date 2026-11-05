(ns gossip.wire-test
  (:require [clojure.test :refer :all]
            [gossip.wire :as wire]
            [udp-wrapper.core :as udp]))

(deftest malformed-utf8-is-rejected
  (let [packet (udp/packet (byte-array [(unchecked-byte 0xc3) (byte 0x28)])
                           (udp/make-address "127.0.0.1") 1001)]
    (is (nil? (wire/decode packet)))))

(deftest udp-payload-limit-is-measured-in-bytes
  (let [sent (atom 0)]
    (with-redefs [udp/send-message (fn [& _] (swap! sent inc))]
      (wire/send! nil "127.0.0.1:1001" (apply str (repeat wire/max-packet-size "x")))
      (is (= 1 @sent))
      (is (thrown? clojure.lang.ExceptionInfo
                   (wire/send! nil "127.0.0.1:1001"
                               (apply str (repeat 32754 "λ")))))
      (is (= 1 @sent)))))

(deftest invalid-identifiers
  (doseq [id [nil "" "localhost" "localhost:-1" "localhost:65536"
              "1.2.3.999:1001" "bad host:1001" "[not-ipv6]:1001"]]
    (is (nil? (wire/parse-id id))))
  (doseq [[host port] [[nil 1001] [123 1001] ["127.0.0.1" "1001"]]]
    (is (thrown? clojure.lang.ExceptionInfo (wire/node-id host port)))))
