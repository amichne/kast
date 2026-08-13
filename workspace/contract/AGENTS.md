# Workspace contract module guide

`:workspace:contract` owns detached host-neutral identities and outcomes for workspace-scoped
operations. It does not own workspace discovery, IntelliJ state, admission implementations, or
physical effects.

## Module map

- `SemanticReadLease.kt` owns canonical-root evidence and the generation-bound semantic read
  lease.
- `WorkspaceSearchScopeModel.kt` owns detached, exact Gradle project/source-set/root ownership
  and model-declared authored/generated provenance.

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
- A compiled search-scope model requires a complete imported model, known source provenance,
  workspace-contained roots, and one coherent Gradle project owner per exact root.
- Contract values are immutable detached data and retain no live host object or callback.

## Verification ladder

1. Run `./gradlew :workspace:contract:test --tests io.github.amichne.kast.workspace.contract.SemanticReadLeaseContractTest`.
2. Run `./gradlew :workspace:contract:test --tests '*SourceRoot*PolicyTest'` after scope-model changes.
3. Run `./gradlew :workspace:contract:test`.
4. Run `./gradlew verifyKastArchitecture --configuration-cache`.
5. Run direct SPI and adapter consumers after changing a public contract.
