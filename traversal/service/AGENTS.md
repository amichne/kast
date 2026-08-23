# Traversal service module guide

`:traversal:service` owns the pure deterministic traversal engine and its module-private
`OneHopRelationReader` port. It has no platform adapter and no mutable repository state.

## Invariants

- Breadth-first frontier and emitted records have canonical ordering independent of reader map
  insertion order.
- Exact endpoint fingerprints terminate cycles; a node is expanded at most once per continuation
  lineage.
- The reader receives only the plan's exact scope, meaning, generation, continuation, and the
  remaining aggregate capacity attenuated into a bounded one-hop budget. Divergent output is a
  closed contract rejection.
- Aggregate bounds stop before another bounded read could exceed the request and return qualified
  continuation state; they never trigger stronger effects.
- Qualified one-hop coverage stops traversal and remains qualified until its bound relation page
  is resumed.

## Verification ladder

1. Run `./gradlew :traversal:service:test --tests '*TraversalServiceTest'`.
2. Run `./gradlew :traversal:contract:test :traversal:service:test`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
