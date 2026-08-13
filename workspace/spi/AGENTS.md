# Workspace SPI module guide

`:workspace:spi` owns host-neutral capabilities that admit and validate workspace-scoped
operations. Implementations belong to physical adapters; this module defines no host integration.

## Module map

- `RuntimeLivenessAuthority.kt` owns bounded runtime and EDT-heartbeat admission.
- `SemanticReadFreshnessAuthority.kt` keeps smart, dumb, transitioning, and blocked source states
  separate from runtime, relation, and graph readiness.
- `SemanticReadLeaseAuthority.kt` owns closed admission, currentness, and invalidation protocols.
- `SemanticReadExecutor.kt` owns the bracketed open-run-revalidate-close transition.

## Dependency boundary

- Production depends only on and exports `:workspace:contract`.
- Do not import IntelliJ, Gradle, JDBC, filesystem, process, transport, serialization, legacy
  `analysis-api`, server, backend, adapter, or service-locator types.
- SPI contracts may describe physical state but perform no I/O and own no implementation object.

## Contract invariants

- Admission returns an open lease capability or finite failure, never null, Boolean, or expected
  exception.
- Runtime liveness is admitted before any semantic lease. A frozen EDT, disposed runtime,
  interruption, or unavailable probe returns finite data within the local heartbeat deadline.
- Smart-index reads reject dumb mode. Only an operation whose result explicitly carries qualified
  incomplete evidence may request `QUALIFIED_DUMB_MODE`; transition and blocked states still fail.
- An open lease exposes only detached root/generation evidence. Adapter handles remain private.
- Executor completion revalidates the exact open lease after work and discards a result when the
  root or publication moved.
- Every admitted lease is closed exactly once, including rejected completion and unexpected
  operation failure.

## Verification ladder

1. Run `./gradlew :workspace:spi:test --tests '*RuntimeLivenessAdmissionTest' --tests '*SemanticReadExecutorTest'`.
2. Run `./gradlew :workspace:contract:test :workspace:spi:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
4. Run adapter consumers after changing a public SPI.
