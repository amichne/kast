---
type: Runtime Flow
title: Semantic change lifecycle
description: A supported semantic intent becomes a source effect only after pure planning and exact preimage admission, then must discharge verification or retain recovery evidence.
resource: file://change
tags: [change, mutation, verification, recovery]
timestamp: 2026-09-11T00:00:00Z
code_sources:
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/admission/ChangeIntent.kt
    symbols: [ChangeIntent]
  - path: change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
    symbols: [PureAddDeclarationPlanningService]
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt
    symbols: [DerivedMutationPostimage]
  - path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt
    symbols: [VerifiedMutationService]
  - path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt
    symbols: [AddDeclarationRecoveryService]
  - path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/RecoveryPreWriteObservation.kt
    symbols: [ConfirmedRecoveryPreimage, RecoveryPreWriteObservationPort]
  - path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/ChangePlanIssuance.kt
---

# Semantic change lifecycle

```text
intent -> pure plan -> observe exact preimage -> admit deterministic postimage
       -> IDE write -> semantic verification -> publish generation
                              \-> durable recovery evidence on interruption
```

The plan retains target identity, expected source state, and verification obligations. Apply admission rejects mismatched workspace roots, stale generations, changed content, invalid provenance, unplanned writes, bad ranges, overlap, and preimage mismatch. The IntelliJ adapter receives only admitted effects.

Verification, not a successful filesystem call, establishes completion. If the effect boundary becomes uncertain, recovery uses durable records to classify and converge the state.

See [semantic change](../modules/change.md) and [evidence authority](../glossary/evidence-authority.md).

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
