# gossip

`gossip` is a Clojure library to do gossip dissemination
of messages in a decentralized manner over unreliable networks.

It is based on the paper [Peer-to-Peer Membership Management
for Gossip-Based Protocols](http://pages.saclay.inria.fr/laurent.massoulie/ieee_tocs.pdf) 

[![Clojars Project](https://clojars.org/gossip/latest-version.svg)](https://clojars.org/gossip)

[Latest codox API docs](https://skeuomorf.github.io/gossip/)

**WARNING WARNING WARNING: The messages gossiped between nodes
are not encrypted, they are sent as plaintext, use at your own
discretion.**

## Usage

```clojure
;;;; Note: To accurately witness the dissemination
;;;; capabilities, there should be a non-trivial amount
;;;; of nodes in the network. The following example
;;;; sacrifices that for brevity's sake but you
;;;; should be able to expand it on your own easily,
;;;; the library is intended to be a low-level building
;;;; block for customized abstractions after all.

(require '[gossip.core :refer [create-node send-initial
                               handle-message
                               schedule-heartbeat-send
                               schedule-heartbeat-dec
                               get-id gossip]]
         '[gossip.utils :refer [receive]]
         '[udp-wrapper.core :refer [create-udp-server close-udp-server
                                    empty-packet localhost]])

;; Get this machine's ip address as a string.
(def ip (.getHostAddress (localhost)))

;; Create nodes with this machine's ip address and different port numbers.
(def node1 (ref (create-node ip 6601)))
(def node2 (ref (create-node ip 6602)))

;; Create a socket for each node.
(def socket1 (create-udp-server 6601))
(def socket2 (create-udp-server 6602))

;; Start a receiving loop for each node, handle-message
;; does pattern matching on the received messages and
;; dispatches accordingly.
(def fut1 (receive socket1 (empty-packet 512) node1 handle-message))
(def fut2 (receive socket2 (empty-packet 512) node2 handle-message))

;; Send a heartbeat every 30 seconds to all nodes in
;; each node's partial-view
(schedule-heartbeat-send socket1 node1 (* 1000 60 5))
(schedule-heartbeat-send socket2 node2 (* 1000 60 5))

;; Decrease the heartbeat-counter of each node
;; every 5 minutes.
(schedule-heartbeat-dec socket1 node1 (* 1000 60 5))
(schedule-heartbeat-dec socket2 node2 (* 1000 60 5))

;; Send an InitialSubscription from node1 to node2.
(send-initial socket1 node1 (get-id @node2))

;; Gossip the message to all nodes in node1's partial-view.
(gossip socket1 node1 "No one shall expel us from the paradise that Cantor has created.")

;; Shutdown
(future-cancel fut1)
(future-cancel fut2)
(close-udp-server socket1)
(close-udp-server socket2)
```

## TODO

* Survey the Clojure testing facilities and decide on one.
* Seriously! MOAR TESTS!
* DTLS.
* Implement indirection.
* Implement lease functionality.
* Decouple the design parameter?

## License
[MIT](./LICENSE)
