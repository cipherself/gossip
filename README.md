# gossip

`gossip` is a Clojure library to do gossip dissemination
of messages in a decentralized manner over unreliable networks.

It is based on the paper [Peer-to-Peer Membership Management
for Gossip-Based Protocols](./scamp.pdf)
by Ayalvadi J. Ganesh, Anne-Marie Kermarrec, and Laurent Massoulié.
The included PDF is from the [author's university website](https://people.maths.bris.ac.uk/~maajg/ieeetocs03-scamp.pdf)
and remains subject to its own copyright.

[![Clojars Project](https://clojars.org/gossip/latest-version.svg)](https://clojars.org/gossip)

[Latest codox API docs](https://cipherself.github.io/gossip/)

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
(def fut1 (receive socket1 (empty-packet 65507) node1 handle-message))
(def fut2 (receive socket2 (empty-packet 65507) node2 handle-message))

;; Send a heartbeat every 30 seconds to all nodes in
;; each node's partial-view
(def heartbeat-send1 (schedule-heartbeat-send socket1 node1 30000))
(def heartbeat-send2 (schedule-heartbeat-send socket2 node2 30000))

;; Decrease the heartbeat-counter of each node
;; every 5 minutes.
(def heartbeat-dec1 (schedule-heartbeat-dec socket1 node1 (* 1000 60 5)))
(def heartbeat-dec2 (schedule-heartbeat-dec socket2 node2 (* 1000 60 5)))

;; Send an InitialSubscription from node1 to node2.
(send-initial socket1 node1 (get-id @node2))

;; Gossip the message to all nodes in node1's partial-view.
(gossip socket1 node1 "No one shall expel us from the paradise that Cantor has created.")

;; Shutdown
(doseq [job [heartbeat-send1 heartbeat-send2 heartbeat-dec1 heartbeat-dec2]]
  (overtone.at-at/stop job))
(future-cancel fut1)
(future-cancel fut2)
(close-udp-server socket1)
(close-udp-server socket2)
```

## Configuration and testing

Tests use `clojure.test` and `test.check` for generated regression cases.
Run `lein test` to execute the unit, simulated-network,
and loopback UDP integration tests. The simulations cover joins, departures,
duplicate subscriptions, isolation recovery, dropped requests, leases, and
indirection.

IDs accept IPv4, bracketed IPv6, and hostnames with ports from 1 through 65535.
Malformed datagrams are ignored. Payloads preserve UTF-8 text and `--`
separators, up to 65507 bytes including the protocol prefix. The receive buffer
must fit the largest supported packet. Receiver errors are reported via an
optional `{:on-error (fn [error] ...)}` argument to `receive`.

Configure subscription redundancy with `:c` in `create-node` (default 10,
range 0..1000). Use the same value throughout a group. It controls extra
subscription copies and the `c + 1` incoming edges removed during departure.
Stop scheduled maintenance before calling `unsubscribe` and closing the socket.

## TODO

* DTLS and peer authentication, once deployment requirements are defined.

## Subscription leases

Set `:lease-duration-ms` on every node in a group to enable leases, for example
`(create-node ip port :c 1 :lease-duration-ms 60000)`. The default is disabled.
Lease-enabled nodes exchange subscription generations and absolute expiry times.
Peers need synchronized clocks. Durations must be 1..86400000 milliseconds.
For an edge A → B, both A's outgoing record and B's incoming record use B's
subscription generation and deadline. The contact returns its lease information
to the subscriber, and accepted edges acknowledge that destination lease.

Run `(schedule-lease-maintenance socket node 1000)` for each node. Keep its
returned job handle and stop it with `overtone.at-at/stop` during shutdown.
Maintenance expires dead peers and renews through a known peer. Lost renewal
requests are retried until acknowledged. Renewals retain the subscriber's
partial view and omit the extra `c` subscription copies described for new joins.
If the entire partial view expires, the node rejoins through a remembered
bootstrap or former peer and establishes a new contact edge. Ordinary renewals
retain the current view. This recovery extension handles simultaneous expiry.
The heartbeat jobs remain necessary for isolation recovery.

Lease messages require this protocol version on every participating node.
Generation records remain in memory to reject delayed stale subscriptions.
They currently persist for the lifetime of the node.

## Subscription indirection

Enable `:indirection? true` on every node to distribute joins arriving through
shared bootstrap nodes. A weighted walk starts with twice the bootstrap node's
partial-view size and chooses the final contact. The subscriber receives the
chosen contact and updates its outgoing contact edge. Walks stop early at nodes
with no outgoing peers. If a walk ends on its subscriber, it takes one additional
hop to avoid choosing the subscriber as its own contact.

Run `(schedule-weight-refresh socket node 1000)` on each node and stop its
returned job during shutdown. Incoming and outgoing arc weights are normalized
separately and exchanged with their peers, as in the paper's asynchronous
weight-update algorithm. New edges start with the mean weight of existing edges.
The initial walk is capped at 20000 hops. No node needs a global member list.
An empty bootstrap keeps its first subscriber directly, establishing a path in
both directions before it handles further joins. This initialization extension
also applies with indirection and leases disabled.

Indirection is disabled by default. It works with or without leases, and requires
this protocol version on all participating nodes.

## Simulation

Run `lein simulate` to compare random contacts, a shared bootstrap, and weighted
indirection with a lossless packet queue. Supply options as one EDN map.

```sh
lein simulate '{:sizes [100 500] :seeds [1 2 3] :modes [:indirect] :seed-size 1}'
lein simulate '{:sizes [100] :modes [:indirect] :weight-rounds 20}'
lein simulate '{:sizes [100 500] :modes [:random :indirect] :leases? true}'
```

`:seed-size` selects an empty bootstrap (1) or a directed ring.
`:weight-rounds` controls refresh sweeps every ten joins.
`:settle-rounds` controls additional refresh sweeps after growth.
Output includes view sizes, connectivity, reciprocal edge metadata, and weighted
walk diagnostics. Walks that reach an empty view count as routing failures.

## License
[MIT](./LICENSE)
