---
type: Kotlin Module Group
title: Semantic change
description: Supported source changes move through closed intent, pure planning, exact admission, effectful application, verification, and recovery.
resource: file://change
tags: [kotlin, mutation, verification, recovery]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt
    symbols: [ChangeIntent, ChangeVerificationObligation]
  - path: change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
    symbols: [PureAddDeclarationPlanningService]
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt
    symbols: [MutationAdmissionFailure, DerivedMutationPostimage]
  - path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt
    symbols: [VerifiedMutationService]
  - path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt
    symbols: [AddDeclarationRecoveryService]
---

# Semantic change

The change family excludes arbitrary text-edit intent. Each supported mutation retains an exact target, source precondition, workspace identity, semantic lease, planned write set, and verification obligations.

Planning is pure. Application first derives a deterministic postimage from the observed preimage and rejects stale roots, generations, content, provenance, overlaps, or mismatched expected text. Verification establishes the resulting semantic state before publication. Recovery consumes durable evidence rather than guessing what an interrupted write accomplished.

Read [change lifecycle](../flows/change-lifecycle.md) for the phase sequence.
