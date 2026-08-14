# Change plan service guide

This module coordinates a detached operation planner with the durable change
journal. It owns orchestration only: no IntelliJ, JDBC, filesystem,
source-write, approval wait, or workspace-transition authority belongs here.

## Invariants

- Invoke the journal only after the planner has returned detached evidence and
  released its read lease and every live IntelliJ value.
- Persist canonical `PlannedAddDeclaration` bytes immediately; return only a
  typed durable record, typed planning rejection, or typed journal rejection.
- Replanning identical G0 evidence is idempotent through canonical PlanId and
  exact stored bytes.
- Approval consumes only the journal command containing PlanId, exact prior
  version, and explicit PlanId-bound evidence.

## Verification

Run `./gradlew :change:plan:service:test`.
