# Workspace contract module guide

`:workspace:contract` owns detached host-neutral identities and outcomes for workspace-scoped
operations. It does not own workspace discovery, IntelliJ state, admission implementations, or
physical effects.

## Module map

- `SemanticReadLease.kt` owns canonical-root evidence, the generation-bound semantic read lease,
  and the closed guard result for effects that require that lease to remain current.
- `SourceRoot.kt` owns the clean-slate detached source-root proof, exact Gradle source-set
  ownership, and authored/generated/qualified-unknown provenance.
- `WorkspaceSearchScopeModel.kt` owns the generation-bound source-root model compiled into native
  symbol and relation search scopes.
- `WorkspaceResourcePolicy.kt` owns validated admission limits and pressure thresholds.
- `WorkspaceResourceObservation.kt` owns detached resource observations, blockers, recovery
  actions, and separately timed admission outcomes.

## Dependency boundary

- Production depends only on `:kernel` and exports that dependency.
- Do not import IntelliJ, Gradle, JDBC, filesystem-write, process, transport, serialization,
  legacy `analysis-api`, server, backend, adapter, or service-locator types.
- JDK path values may enter only through the documented canonical-root proof boundary. The
  contract performs no filesystem I/O.

## Contract invariants

- A canonical workspace root is absolute and lexically normalized. The physical adapter that
  resolved symlinks and canonical identity is the only legitimate caller of its boundary parser.
- A semantic read lease always carries both canonical root and published evidence generation.
- A guarded effect returns `Completed` only for the exact current ready lease; absence, lifecycle
  movement, root mismatch, or generation change returns `Moved` without invoking the effect.
- Every clean-slate source root is workspace-contained, preserves exact model ownership, and
  carries Authored, Generated, or a finite reason for Unknown provenance. Platform-invalid raw
  path text is rejected before a `SourceRoot` can be constructed.
- Resource policy admits only validated positive limits, bounded percentages, and non-negative
  counts. Ordinary reads are not an expensive-work kind and cannot consume an initiation slot.
- Heap, EDT, capacity, queue, timeout, interruption, and initiation failure remain distinct typed
  blockers with explicit recovery actions.
- Contract values are immutable detached data and retain no live host object or callback.

## Verification ladder

1. Run `./gradlew :workspace:contract:test --tests io.github.amichne.kast.workspace.contract.SemanticReadLeaseContractTest`.
2. Run `./gradlew :workspace:contract:test --tests '*SourceRoot*PolicyTest'` after scope-model changes.
3. Run `./gradlew :workspace:contract:test --tests '*WorkspaceResourcePolicyTest'` after resource-contract changes.
4. Run `./gradlew :workspace:contract:test`.
5. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
6. Run direct SPI and adapter consumers after changing a public contract.
