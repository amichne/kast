# Topology service module guide

`:topology:service` owns pure deterministic graph algorithms over detached published topology.
It owns no build initiation, extraction, persistence, platform object, workspace transition, or
protocol transport.

## Invariants

- Reachability, cycles, strongly connected components, condensation order, and quotient graphs
  are module-internal algorithms over an already eligible detached snapshot read.
- Canonical ordering is independent of SQLite row order and map insertion order.
- Every bounded or rejected result remains closed typed data.

## Verification

1. Run `./gradlew :topology:service:test`.
2. Run `./gradlew :traversal:service:test :topology:service:test` after traversal changes.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
