# Workspace service module guide

`:workspace:service` owns the deterministic event-driven transition coordinator. It has no live
IDE, filesystem, Gradle, database, transport, or aggregate-backend authority.

## Invariants

- Signals invalidate and conflate; one active cycle settles, refreshes, captures, reconciles,
  verifies, and publishes through narrow ports.
- New events withdraw readiness without waiting and prevent an older candidate from publishing.
- Publication is one begin, prepare, commit-or-discard protocol through `:evidence:spi`.
- Cancellation escapes; finite retry and blocker outcomes retain pending work.

## Verification ladder

1. Run `./gradlew :workspace:service:test --tests '*WorkspaceTransitionCoordinatorTest'`.
2. Run `./gradlew :workspace:contract:test :workspace:spi:test :workspace:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
4. Run the indexer ingress and worker transition suites.
