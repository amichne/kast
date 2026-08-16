# Relation contract module guide

`:relation:contract` owns the detached host-neutral contract for one-hop semantic reads. It does
not own IntelliJ, traversal, persistence, transport, workspace transitions, or mutation.

## Invariants

- A relation request starts only from `SymbolSelector`; names, paths, offsets, and FQNs are not
  substitute authority.
- `References`, `Callers`, `Callees`, `Implementations`, `Inheritors`, `Overrides`, and `TypeUses`
  are closed meanings, never an arbitrary kind/direction pair.
- Every fact preserves exact compiler-grounded source and target endpoints, one exact occurrence,
  the subject generation, and model-derived provenance.
- Only terminal limitation-free enumeration proves exact absence. Every incomplete enumeration is
  qualified with a non-empty limitation set and a subject/meaning/generation-bound continuation.
- Relation continuations resume one operation; they are not traversal state.

## Verification ladder

1. Run `./gradlew :relation:contract:test`.
2. Run `./gradlew :relation:service:test :relation:intellij:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
