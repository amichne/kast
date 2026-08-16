# Change verification service guide

`:change:verify` owns KCS-018 reconciliation of one `AppliedUnverified` AddDeclaration against a
distinct resulting workspace generation and complete detached semantic evidence.

## Dependency boundary

- Production depends only on `:change:apply`, `:change:contract`, `:diagnostic:contract`,
  `:relation:contract`, and `:workspace:contract`.
- Do not import IntelliJ, filesystem, JDBC, SQLite, recovery implementation, workspace service,
  runtime, transport, legacy verification, journal, or callback types.
- Resulting-generation publication and semantic observation are explicit narrow ports. This module
  performs neither effect itself.

## Invariants

- A plan and applied state must match before any resulting publication is requested.
- The resulting publication must preserve the exact canonical root and be strictly newer than G0.
- Workspace, relation, and diagnostic coverage remain attached as stronger proof types.
- Relation evidence is complete, result-generation-bound, target-bound, and semantically unchanged
  outside the accepted declaration addition.
- Diagnostic evidence is complete, exact-scope, result-generation-bound, and error-free.
- The observed declaration identity must exactly equal the plan's expected semantic delta.
- Only the exhaustive obligation evaluator may issue `VerifiedReceipt`.

## Verification

Run `./gradlew :change:verify:test`.
