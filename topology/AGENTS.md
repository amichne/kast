# Topology module family guide

`topology` owns explicit, generation-bound repository graph construction and pure graph reads.
Its four included projects separate detached contracts, the sole build authority, K2 extraction,
and host-neutral algorithms.

## Dependency and authority boundaries

- `:topology:contract` is the inward detached contract leaf.
- `:topology:build` owns the private authority behind `topology.build`; it coordinates through
  ports and imports no IntelliJ, JDBC, filesystem, or Gradle implementation.
- `:topology:intellij` enumerates admitted source roots and performs request-local K2 extraction.
- `:topology:service` computes traversal, SCC, condensation, and quotient evidence from detached
  snapshot content.
- Only `:evidence:sqlite` may implement durable topology publication. Runtime composition may wire
  the ports but cannot construct topology or publish a snapshot itself.

## Durable invariants

- Only complete per-file compiler coverage can become a `CompleteTopologyGeneration`.
- Exact-identity snapshots may be reused directly. A stale snapshot may be rebound to a current
  lease only when current source-root enumeration proves the same canonical file content.
- Source edits, failed extraction, cancellation, and moved leases cannot acquire publication
  authority or replace the last good snapshot.
- Public repository traversal reads eligible SQLite evidence and performs no K2 work.

## Verification

1. Run `./gradlew :topology:contract:test :topology:build:test :topology:intellij:test :topology:service:test`.
2. Run `./gradlew :evidence:sqlite:test :runtime:composition:test :traversal:service:test`.
3. Run `./gradlew topologyAcceptance`.
