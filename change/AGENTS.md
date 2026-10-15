---
type: Module Guide
title: "Kast change"
description: "Owns typed source mutation through explicit intent, planning, application, receipt, and recovery phases."
resource: "repo://change"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: change/apply/build.gradle.kts
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt
  - path: change/contract/build.gradle.kts
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveAddDeclarationPlanCodec.kt
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/AddDeclarationChangePlan.kt
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt
  - path: change/intellij/build.gradle.kts
  - path: change/plan/build.gradle.kts
  - path: change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
  - path: change/protocol/build.gradle.kts
  - path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt
  - path: change/protocol/src/test/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocolTest.kt
  - path: change/recovery/build.gradle.kts
  - path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt
  - path: change/verify/build.gradle.kts
  - path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 8fe5e439c71c -->

# change

## Purpose

Owns typed source mutation through explicit intent, planning, application, receipt, and recovery phases.

## Key Files

- [ChangeIntent.kt](contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt) - closed mutation intents.
- [AddDeclarationChangePlan.kt](contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/AddDeclarationChangePlan.kt) - an admitted declaration plan.
- [LiveAddDeclarationPlanCodec.kt](contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveAddDeclarationPlanCodec.kt) - versioned live plan encoding.
- [CanonicalChangePlanProtocol.kt](protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt) - canonical request admission and planning.
- [PureAddDeclarationPlanningService.kt](plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt) - pure declaration planning.
- [MutationAdmission.kt](apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt) - exact source, preimage, write set, and mutation admission.
- [VerifiedMutationService.kt](verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt) - plan and applied-state admission for published mutations.
- [AddDeclarationRecoveryService.kt](recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt) - recovery preparation and effects.

## Subdirectories

- [contract/](contract/) - intents, proof, plans, codecs, and finite outcomes.
- [plan/](plan/) - pure planners and live orchestration.
- [protocol/](protocol/) - canonical planning admission and bounded previews.
- [apply/](apply/) - admitted mutation effects.
- [verify/](verify/) - receipt and post-effect proof.
- [recovery/](recovery/) - durable recovery preparation and execution.
- [intellij/](intellij/) - document, write, and rollback adapters.

## Entry Points

- Gradle projects: `:change:contract`, `:change:plan`, `:change:protocol`, `:change:apply`, `:change:verify`, `:change:recovery`, and `:change:intellij`.
- [CanonicalChangePlanProtocolTest.kt](protocol/src/test/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocolTest.kt) - canonical planning cases.

## Navigation Hints

- Trace live changes from the public tool into hosted composition, canonical planning, exact stored plans, admitted writes, and recovery.
- The hosted runtime composes live receipts and durable stores. Published-workspace verification remains a distinct path.
- Preserve the stored plan identity through application. Keep rejection, applied state, and recovered state distinct.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
