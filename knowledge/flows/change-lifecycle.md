---
type: Runtime Flow
title: Semantic change lifecycle
description: A supported semantic intent becomes a source effect only after pure planning and exact preimage admission, then must discharge verification or retain recovery evidence.
resource: file://change
tags: [change, mutation, verification, recovery]
timestamp: 2026-09-13T00:00:00Z
code_sources:
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPlanningEvidenceFailure.kt
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
  - path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/LiveWriteObservation.kt
  - path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/LiveIntellijDocumentSession.kt
  - path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/PhysicalWriteCompletion.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeApprovals.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeApply.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeVerification.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeRecovery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/WorkspaceExecution.kt
    symbols: [WorkspaceExecution]
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/SettledOutputRejection.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/HostedPlanApprovalGateway.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/codex/CodexPlanApprovalProjection.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/BrokerTrustEnrollment.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
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
do not grant write authority. Concurrent requests retain distinct challenges for
the same plan; only the cryptographically matched challenge is consumed.

Apply compares the plan with a fresh live root, host, epoch, content view and
model, then observes the exact saved preimage. `LiveMutationAuthority` retains
that proof and the approved write set. A pre-write observation guards the
IntelliJ write command; the adapter receives only the admitted single-file effect.
Durable pre-write and applied records retain recovery evidence across effects.

The IntelliJ command group belongs to one document mutation session. Mutation
and any local restoration share that group; separate admitted attempts have
distinct group identities, so consecutive changes remain separate Undo steps.

The IDE save can enqueue an asynchronous VFS write. Before reading the physical
postimage, the adapter calls the pinned platform's per-file
`ManagingFS.flushPendingUpdates(file)` outside EDT. A failed completion prevents
physical observation. Completion and exact postimage refinement emit typed
success or finite rejection causes without source content, paths, or approval
payloads. Document save flags alone do not prove physical write completion.

A successful source write is not completion. Apply reacquires a live read,
re-observes compiler identity, diagnostics, relation/traversal obligations and
source content, then persists the verified receipt. `Verified` carries the
receipt identity. `AppliedUnverified` retains a write whose verification or
receipt persistence failed. `RecoveryRequired` retains an uncertain or previously
attempted write. The latter two remain qualified results.

An uncertain broker invocation leaves its workspace in `WORKSPACE_RECOVERY_REQUIRED`.
The broker rejects further operations for that workspace, including recovery.
Replace the broker while retaining its invocation journal and thread store before
requesting separately approved recovery. The IDE's durable mutation records remain
authoritative; replacing the broker does not make an attempted plan executable again.
A legacy thread-store migration requires a new Kast conversation before requesting
recovery; retained historical bindings do not authorize resumed execution. Invocation
fences and IDEA mutation evidence remain intact.
An output-contract rejection after a possible write remains uncertain even when
the provider has terminated. The [settled read exception](request-dispatch.md)
uses canonical non-writing effect metadata and grants no mutation retry or recovery
authority.

A repeated verified apply returns its stored receipt without another write,
including when the current IDE owner differs from the historical receipt.
An attempted plan without a verified receipt cannot be applied again. Recovery
requires a new approval and durable records. Qualified manual-recovery-required
results may retain the original plan's historical live evidence when fresh read
admission fails. They do not prove current source state; completed prior-state or
rollback results still require the current owner's fresh source observation.
Recovery can prove the exact prior state or roll back an exact matching postimage; divergence,
missing records or incomplete observation cannot establish success. Legacy empty
preimages remain ambiguous because they cannot distinguish absence from an
existing empty file.

Change tools are deferred defaults after the installed workflow passed the
[native acceptance matrix](../../docs/reviews/plugin-native-change-acceptance.md). See
[semantic change](../modules/change.md), [evidence authority](../glossary/evidence-authority.md)
and the [App Server compatibility record](../../app-server/docs/compatibility.md).

Hosted change planning restores its exact target through the same project-owned reference transport as semantic reads. A compact handle is expanded and subjected to canonical authority validation before exact description and planning evidence acquisition. Handle lookup does not authorize applying a plan.

Hosted `AddDeclaration` planning acquires its own current relation, traversal and diagnostic evidence. Complete evidence alone can issue an executable plan. Rejected or incomplete evidence retains all finite reasons in the bounded hosted planning detail. A page continuation is not a complete evidence proof: the current planning boundary reports `COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE` rather than treating a terminal page as the complete relation set. Prior public reads do not satisfy or alter planning prerequisites.

A daemon preparation rejection is a known pre-execution failure: the requested
semantic operation was not sent. Its finite cause and preparation identity survive
Codex projection. A transport failure after semantic dispatch remains uncertain and
is never automatically replayed. Apply and recovery approval challenges use the
same workspace demand before loading the immutable hosted plan. Preparation failure
retains its finite cause and operation identity, before any controller prompt or
signature. The preparation deadline includes workspace readiness; the separate
controller deadline begins only after a challenge is admitted.

Installation creates the broker signing identity before activating the product. Reinstallation preserves an admitted matching pair; partial or conflicting enrollment fails closed without replacing surviving keys. Runtime apply and recovery only read existing enrolled authority.
