# Traversal contract module guide

`:traversal:contract` owns the detached host-neutral plan, bounds, continuation, record, coverage,
and public `traversal.run` contract. It does not own relation providers, platform adapters,
persistence, diagnostics, or mutation.

## Invariants

- A plan starts from one exact `SymbolSelector` and preserves its lease and `SymbolSearchScope`.
- One closed `RelationMeaning` applies to every hop; traversal cannot synthesize a kind/direction.
- Record, byte, work, elapsed-time, depth, frontier, and per-hop bounds are finite typed inputs.
- Complete means the deterministic frontier is exhausted under complete one-hop coverage.
- Every stopped frontier or incomplete one-hop page is qualified with a continuation bound to the
  exact start selector, meaning, root, generation, and scope.

## Verification ladder

1. Run `./gradlew :traversal:contract:test`.
2. Run `./gradlew :traversal:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
