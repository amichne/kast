<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 8fe5e439c71c -->

# change

## Purpose

Implements proof-carrying source mutation as explicit planning, application, verification, and recovery phases.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt](contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt) - closed mutation intent boundary.
- [contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/AddDeclarationChangePlan.kt](contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/AddDeclarationChangePlan.kt) - admitted add-declaration plan.
- [plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt](plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt) - pure planning example.
- [apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt](apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt) - effect admission.
- [verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt](verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt) - post-effect verification.
- [recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt](recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt) - recovery path.

## Subdirectories

- `contract` - intents, plans, proofs, codecs, and typed outcomes.
- `plan` - pure planners for supported mutations.
- `protocol` - canonical planning admission, lowering, and bounded preview projection through narrow host ports.
- `apply` - admitted write effects and observations.
- `verify` - verification contracts and published-path obligation discharge and successor publication; live verification is composed in `runtime/hosted`.
- `recovery` - durable recovery preparation and execution.
- `intellij` - document/write adapters and rollback effects.
- [intellij/src/nativeFixture](intellij/src/nativeFixture) - isolated native IDE acceptance probe; packaged separately from the product plugin.
- [intellij/src/nativeFixtureTest](intellij/src/nativeFixtureTest) - probe request, response, and readiness contract tests.

## Entry Points

- Gradle projects: `:change:contract`, `:change:plan`, `:change:protocol`, `:change:apply`, `:change:verify`, `:change:recovery`, `:change:intellij`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/change.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Trace a mutation in phase order: contract -> plan -> apply -> verify -> recovery.
- For IDE write behavior, inspect `intellij` only after the admitted plan and proof types are clear.
- For native fixture readiness or document probes, start in `intellij/src/nativeFixture`, then follow the [native acceptance runner](../packaging/run-hosted-change-acceptance.py) and [probe client](../packaging/native_fixture_probe.py).
