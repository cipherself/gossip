(ns gossip.wire
  (:require [clojure.string :as string]
            [udp-wrapper.core :as udp])
  (:import (java.net InetAddress)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets CodingErrorAction CharacterCodingException)))

(def max-packet-size 65507)

(defn valid-port? [port]
  (and (integer? port) (<= 1 port 65535)))

(defn valid-host? [host]
  (and (string? host)
       (<= 1 (count host) 253)
       (if (string/includes? host ":")
         (try (instance? java.net.Inet6Address (InetAddress/getByName host))
              (catch Exception _ false))
         (if (re-matches #"[0-9.]+" host)
           (let [parts (string/split host #"\." -1)]
             (and (= 4 (count parts))
                  (every? #(and (re-matches #"[0-9]{1,3}" %)
                                (<= (Integer/parseInt %) 255)) parts)))
           (every? #(boolean (re-matches #"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" %))
                   (string/split host #"\." -1))))))

(defn parse-id [id]
  (when (string? id)
    (when-let [[_ host port] (or (re-matches #"\[([^\]]+)\]:([0-9]{1,5})" id)
                                (re-matches #"([^:]+):([0-9]{1,5})" id))]
      (let [port (Integer/parseInt port)]
        (when (and (valid-host? host) (valid-port? port))
          [host port])))))

(defn require-id [id]
  (or (parse-id id) (throw (ex-info "Invalid node ID" {:id id}))))

(defn node-id [host port]
  (let [id (str (if (string/includes? host ":") (str "[" host "]") host) ":" port)]
    (require-id id)
    id))

(defn send! [socket id message]
  (let [[host port] (require-id id)]
    (when-not (string? message)
      (throw (ex-info "Messages must be strings" {})))
    (let [bytes (udp/get-bytes-utf8 message)]
      (when (> (alength bytes) max-packet-size)
        (throw (ex-info "Message exceeds UDP payload limit" {:bytes (alength bytes)})))
      (udp/send-message socket (udp/packet bytes (udp/make-address host) port)))))

(defn decode
  "Decode one UTF-8 datagram, preserving everything after the first separator."
  [packet]
  (try
    (when (<= (.getLength packet) max-packet-size)
      (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                      (.onMalformedInput CodingErrorAction/REPORT)
                      (.onUnmappableCharacter CodingErrorAction/REPORT))
            bytes (ByteBuffer/wrap (.getData packet) (.getOffset packet) (.getLength packet))
            text (str (.decode decoder bytes))]
        (string/split text #"--" 2)))
    (catch CharacterCodingException _ nil)))

(defn encode-subscription [{:keys [id epoch expires-at]}]
  (str id "|" epoch "|" expires-at))

(defn decode-subscription [payload]
  (when (string? payload)
    (let [[id epoch expires-at :as fields] (string/split payload #"\|" -1)]
      (when (and (= 3 (count fields)) (parse-id id)
                 (re-matches #"[0-9]{1,18}" epoch)
                 (re-matches #"[0-9]{1,18}" expires-at))
        (let [epoch (Long/parseLong epoch)
              expires-at (Long/parseLong expires-at)]
          (when (< epoch expires-at)
            {:id id :epoch epoch :expires-at expires-at}))))))
