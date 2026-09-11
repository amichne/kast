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
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveAddDeclarationChangePlan.kt
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
  - path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalLiveChangePlanProtocol.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeCoordinator.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeVerification.kt
---

# Semantic change

The change family excludes arbitrary text-edit intent. Each supported mutation retains an exact target, source precondition, workspace identity, explicit published or live planning basis, planned write set, and verification obligations.

The installed published path retains `EditableMutationTarget` and its
`SemanticReadLease`. The hosted `AddDeclaration` path has a separate
`LiveAddDeclarationChangePlan` with historical original-owner epoch and project
model evidence. `LiveMutationAuthority` requires fresh matching evidence, exact
source preconditions and verified plan approval before admitting a write.
Neither detached plan basis grants current authority.

Planning is pure. Application first derives a deterministic postimage from the
observed preimage and rejects stale roots, generations or live evidence, content,
provenance, overlaps, or mismatched expected text. Published verification
establishes the resulting semantic state before successor publication. Hosted
verification reacquires live evidence, discharges the required obligations and
persists a receipt with historical before/after evidence; it does not publish a
workspace generation. Recovery consumes durable evidence rather than guessing
what an interrupted write accomplished.

Read [change lifecycle](../flows/change-lifecycle.md) for the phase sequence.

The shared `change/protocol` module owns pure planning-request lowering and
preview projection. The host supplies request admission, preserving the original
reference bytes until its authority boundary, and a narrow durable-plan issuance
port. The existing installed handler retains published selector admission.
`CanonicalLiveChangePlanProtocol` adds live request admission through explicit
host ports. The hosted coordinator owns live plan and receipt persistence,
exact-plan approval verification, source effects and recovery. Shared protocol
code cannot acquire those capabilities.

A missing recovery record cannot establish that no effect occurred. A surviving
pre-write record also remains recovery-required unless a fresh observation proves
the exact saved preimage and, when loaded, the saved and committed document
preimage for every recorded source. Dirty, unavailable, divergent, duplicate, or
incomplete observations reject. Legacy empty preimages remain ambiguous because
the record cannot distinguish an absent file from an existing empty file. The
default observation port is unavailable, so unobserved records fail closed.
