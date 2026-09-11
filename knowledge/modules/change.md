---
type: Kotlin Module Group
title: Semantic change
description: Supported source changes move through closed intent, pure planning, exact admission, effectful application, verification, and recovery.
resource: file://change
tags: [kotlin, mutation, verification, recovery]
timestamp: 2026-09-11T00:00:00Z
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
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/EditableMutationTarget.kt
    symbols: [EditableMutationTarget, MutationTargetObservation]
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/AddDeclarationPlanningEvidence.kt
    symbols: [CompleteChangePlanningEvidence]
  - path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/InstalledIntellijChangePorts.kt
  - path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/RecoveryPreWriteObservation.kt
    symbols: [ConfirmedRecoveryPreimage, RecoveryPreWriteObservationPort]
  - path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/ChangePlanIssuance.kt
---

# Semantic change

The change family excludes arbitrary text-edit intent. Each supported mutation retains an exact target, source precondition, workspace identity, published semantic lease, planned write set, and verification obligations.

The read-authority migration does not authorize live writes. Native intent
admission refines an exact selector to a published lease. `EditableMutationTarget`
compares selector authority with the published workspace and retains
`SemanticReadLease`; planning evidence must match that target authority.

Planning is pure. Application first derives a deterministic postimage from the observed preimage and rejects stale roots, generations, content, provenance, overlaps, or mismatched expected text. Verification establishes the resulting semantic state before publication. Recovery consumes durable evidence rather than guessing what an interrupted write accomplished.

Read [change lifecycle](../flows/change-lifecycle.md) for the phase sequence.

The shared `change/protocol` module owns pure planning-request lowering and
preview projection. The host supplies request admission, preserving the original
reference bytes until its authority boundary, and a narrow durable-plan issuance
port. The existing installed handler retains published selector admission.
This extraction does not implement live planning or grant source-write authority.

A missing recovery record cannot establish that no effect occurred. A surviving
pre-write record also remains recovery-required unless a fresh observation proves
the exact saved preimage and, when loaded, the saved and committed document
preimage for every recorded source. Dirty, unavailable, divergent, duplicate, or
incomplete observations reject. Legacy empty preimages remain ambiguous because
the record cannot distinguish an absent file from an existing empty file. The
default observation port is unavailable, so unobserved records fail closed.
