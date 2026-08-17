# CLI module guide

`:cli` owns the one Kotlin `kast` executable and only its public boundary behavior.

## Dependency boundary

- Production depends only on distribution contract/managed, kernel, and protocol
  contract/registry/wire modules plus serialization.
- Do not import semantic services, runtime server implementation, IntelliJ, Gradle, JDBC, SQLite,
  evidence implementations, source writers, aggregate backends, or legacy protocol types.
- Process launch and UDS access are narrow outer adapters. They cannot interpret semantic payloads.

## Contract invariants

- Raw argv refines to exactly one of the eleven canonical operation projections.
- Filesystem discovery returns one canonical, settings-owned repository root before process or UDS
  access.
- Runtime demand and UDS exchange remain exact-root capabilities with closed failures.
- A typed projection owns request parsing, generated wire encoding/decoding, outcome projection, and
  exhaustive exit status without `Any`, maps, unchecked casts, or raw RPC.
- No fallback executable, hidden command, direct SQL, or lifecycle command exists.

## Verification ladder

1. Run `./gradlew :cli:test --tests '*CliBoundaryContractTest'`.
2. Run `./gradlew :cli:nativeTest`.
3. Run `./gradlew :cli:test :cli:nativeTest verifyKastModuleGraph` after architecture admission.
