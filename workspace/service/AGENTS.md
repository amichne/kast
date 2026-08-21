# Workspace service module guide

`:workspace:service` owns deterministic workspace transition and resource-admission coordination.
It has no live IDE, filesystem, Gradle, database, transport, or aggregate-backend authority.

## Invariants

- Signals invalidate and conflate; one active cycle settles, refreshes, captures, reconciles,
  verifies, and publishes through narrow ports.
- New events withdraw readiness without waiting and prevent an older candidate from publishing.
- Publication is one begin, prepare, commit-or-discard protocol through `:evidence:contract`.
- Cancellation escapes; finite retry and blocker outcomes retain pending work.
- Resource admission brackets only expensive initiation; semantic readiness waits and operation
  execution remain outside the controller.
- An active exact root and work kind is reused. Other starts consume only their kind's validated
  capacity, with a bounded waiter count and absolute timeout.
- Every initiation releases its entry exactly once. Queue and admission duration are reported
  separately, and resource pressure returns a typed blocker plus recovery action.

## Verification ladder

1. Run `./gradlew :workspace:service:test --tests '*WorkspaceTransitionCoordinatorTest' --tests '*WorkspaceResourceAdmissionControllerTest'`.
2. Run `./gradlew :workspace:contract:test :workspace:service:test`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
4. Run the indexer ingress and worker transition suites.
