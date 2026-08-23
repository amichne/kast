# Runtime graph protocol guide

This package owns the canonical topology-build handler, relation and traversal
result projection, and the snapshot-only traversal router used by public
`traversal.run`.

## Invariants

- Only `CanonicalTopologyBuildHandler` may invoke topology-build operations.
- `TopologyBackedTraversalOperations` reads an eligible generation-bound SQLite
  snapshot and never falls back to K2 extraction.
- One-hop `relation.read` retains the injected relation authority; moving its
  protocol projection here does not change its backend.
- Protocol handlers preserve exact selectors, explicit budgets, completeness,
  and typed rejection data.

## Verification

1. Run `./gradlew :runtime:composition:test --tests '*KastRuntimeCompositionTest'`.
2. Run `python3 .github/scripts/check-repository-shape.py --root .`.
