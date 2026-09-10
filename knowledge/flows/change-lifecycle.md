---
type: Runtime Flow
title: Semantic change lifecycle
description: A supported semantic intent becomes a source effect only after pure planning and exact preimage admission, then must discharge verification or retain recovery evidence.
resource: file://change
tags: [change, mutation, verification, recovery]
timestamp: 2026-09-09T00:00:00Z
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
