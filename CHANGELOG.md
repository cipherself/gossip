# Changelog

## 0.2.0 — 2026-11-05

### Added

- Optional subscription leases with expiry, renewal retries, and stale-generation rejection.
- Optional weighted subscription indirection for groups using shared bootstrap nodes.
- Per-node subscription redundancy through the `:c` option.
- Validation for node identifiers, ports, protocol messages, and UTF-8 datagrams.
- Property-based, simulated-network, and UDP integration tests.
- A configurable simulator for membership, lease, and heartbeat recovery scenarios.

### Fixed

- Stop forwarding gossip messages that a node has already received.
- Forward declined subscriptions to another peer.
- Maintain reciprocal incoming and outgoing membership records.
- Reject duplicate and self edges, and initialize an empty bootstrap with a reciprocal first edge.
- Handle departures with empty views and apply the `c + 1` removal rule.
- Use the destination subscription's generation and deadline at both ends of a leased edge.
- Start fresh subscription generations after heartbeat isolation timeouts while honoring live older-generation acknowledgements.
- Preserve message payloads containing `--` and restore receive-buffer capacity between datagrams.
- Keep receivers running after malformed messages and handler errors.
- Initialize arc weights when edges are created and discard weights when edges are removed.

### Changed

- Upgrade Clojure to 1.12.6 and udp-wrapper to 0.1.3.
- Use Clojure CLI, tools.build, and deps-deploy for development and publishing.
- Replace the Leiningen build configuration and its legacy development plugins.
- Update usage examples and include a local copy of the SCAMP paper.

### Upgrading

Leases and indirection are disabled by default. Upgrade every participating node
to 0.2.0 before enabling either feature. Use the same `:c` value throughout a
group. Lease-enabled groups require synchronized clocks and scheduled lease
maintenance. Indirection requires scheduled weight refreshes. Heartbeat jobs
provide isolation recovery.

Messages use plaintext UDP. Deployments must provide their own trusted network
boundary. See the README for configuration, scheduling, and shutdown examples.
