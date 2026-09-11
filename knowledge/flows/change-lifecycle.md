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
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/core/AgentSessionBootstrap.kt
    symbols: [HostedToolDefinition]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexSessionProjection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexProtocolAdapter.kt
  - path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveChangeBasis.kt
  - path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangePlanning.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeApply.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeVerification.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeRecovery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/HostedPlanApprovalGateway.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexPlanApprovalProjection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/BrokerTrustEnrollment.kt
---

# Semantic change lifecycle

```text
exact selector -> immutable plan + stored preview -> exact-plan approval
               -> fresh preimage admission -> IDE write -> semantic verification
                                                  -> durable verified receipt
                                                  -> qualified recovery evidence
```

The installed published path retains `SemanticReadLease`, generation admission
and publication after verification. The hosted path supports `AddDeclaration`
into one existing authored Kotlin source file. Its `ChangePlanningBasis.Live`
retains the original live reference and complete project model as historical
evidence. It does not convert an epoch into a published generation or current
write authority.

`change/protocol` owns pure request lowering and preview projection. The hosted
owner restores the exact returned selector under its current read authority,
collects complete planning evidence, constructs an immutable plan and persists
it before returning its identity and preview. The CLI preserves reference bytes
and routes the entire change family before isolated bootstrap. Unsupported
intents and absent hosts reject without worker fallback.

`change_plan` needs no approval. Apply and recovery ask the broker to load the
stored preview and a host challenge. A separate native `fileChange` item and
approval request correlate the current controller with the exact plan, root,
host and operation. Only that controller's accepted decision reaches the signing
gateway. The plugin verifies the Ed25519 assertion against the explicitly
enrolled key and consumes the challenge once. Catalog metadata and plan identity
do not grant write authority.

Apply compares the plan with a fresh live root, host, epoch, content view and
model, then observes the exact saved preimage. `LiveMutationAuthority` retains
that proof and the approved write set. A pre-write observation guards the
IntelliJ write command; the adapter receives only the admitted single-file effect.
Durable pre-write and applied records retain recovery evidence across effects.

A successful source write is not completion. Apply reacquires a live read,
re-observes compiler identity, diagnostics, relation/traversal obligations and
source content, then persists the verified receipt. `Verified` carries the
receipt identity. `AppliedUnverified` retains a write whose verification or
receipt persistence failed. `RecoveryRequired` retains an uncertain or previously
attempted write. The latter two remain qualified results.

A repeated verified apply returns its stored receipt without another write,
including when the current IDE owner differs from the historical receipt.
An attempted plan without a verified receipt cannot be applied again. Recovery
requires a new approval, durable records and fresh source observation. It can
prove the exact prior state or roll back an exact matching postimage; divergence,
missing records or incomplete observation cannot establish success. Legacy empty
preimages remain ambiguous because they cannot distinguish absence from an
existing empty file.

Change tools remain opt-in while native installed acceptance is pending. See
[semantic change](../modules/change.md), [evidence authority](../glossary/evidence-authority.md)
and the [App Server compatibility record](../../app-server/docs/compatibility.md).
