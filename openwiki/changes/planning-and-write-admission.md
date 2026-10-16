---
type: System Explanation
title: Change planning and write admission
description: Explains the separate planning, stored-plan, and native-write proofs
  for controlled source changes.
tags:
- kast
- changes
code_sources:
- path: change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt
- path: change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
- path: change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveChangeBasis.kt
- path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt
- path: change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
- path: change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/HostedLiveSourceWriter.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangePlanning.kt
- path: change/protocol/src/test/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocolTest.kt
- path: change/intellij/src/test/kotlin/io/github/amichne/kast/change/intellij/IntellijSourceWriteProtocolTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-432d05143d371dfe54d7f30d
    resource: repo://change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt
  - id: openwiki-source-74f576bcb417995c91f330ce
    resource: repo://change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt
  - id: openwiki-source-0e4d662ac2bbde832036d7e3
    resource: repo://change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveChangeBasis.kt
  - id: openwiki-source-23a518e99c7e7bac85c836ca
    resource: repo://change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Change planning and write admission

## Ownership and caller flow

The public change adapters submit admitted intent through the existing hosted endpoint.
The hosted coordinator composes live planning, durable stores, native writes, verification, and recovery.
The change modules own the phase-specific contracts and decisions.
Transport code does not gain a write capability from a valid request document.

`CanonicalChangePlanProtocol` owns host-independent admission and projection for canonical planning requests.
It selects the existing planner and issues a stored plan identity after successful derivation.
The live planner also uses `PureAddDeclarationPlanningService`, with a distinct admitted live input.
The live plan keeps detached proof. It does not create a published workspace lease or revive a live owner.

## From a plan to one admitted write

`LiveMutationAuthority.prepare` compares the plan's basis with the current original owner and imported model.
It then examines the exact source identity, saved content, writable state, and planned write entry.
`DerivedMutationPostimage` derives the postimage from the admitted preimage and the planned mutations.
Overlap, out-of-range writes, stale content, and a mismatched write set reject before the native write.

The authority permanently claims the exact plan's application before durable recovery preparation.
A repeated plan returns `AlreadyAttempted`.
If recovery preparation fails after that claim, the result remains `ClaimedWithoutRecovery`.
The caller cannot interpret that state as permission to repeat the write.

The IntelliJ adapter consumes this authority through `IntellijSourceWriteProtocol` and the project document session.
The adapter owns document access, write effects, saved bytes, and bounded phase evidence.
Pure planning does not establish that the IDE accepted or saved the mutation.

## Rejection and extension rules

Keep detached planning evidence separate from current execution authority.
Preserve the issued plan identity through storage, application, and recovery.
For a new mutation kind, extend its admitted intent, pure derivation, write proof, receipt, and recovery path together.
Do not bypass a failed preimage comparison with a new raw source string.

The canonical planner tests establish request lowering and typed rejection.
The native write protocol tests establish the adapter's effect order under their document fixtures.
Neither test alone proves an installed IDEA write in a user repository.

## Focused tests

- [CanonicalChangePlanProtocolTest.kt](../../change/protocol/src/test/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocolTest.kt)
- [IntellijSourceWriteProtocolTest.kt](../../change/intellij/src/test/kotlin/io/github/amichne/kast/change/intellij/IntellijSourceWriteProtocolTest.kt)

## Source owners

- [CanonicalChangePlanProtocol.kt](../../change/protocol/src/main/kotlin/io/github/amichne/kast/change/protocol/CanonicalChangePlanProtocol.kt)
- [PureAddDeclarationPlanningService.kt](../../change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt)
- [LiveChangeBasis.kt](../../change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/LiveChangeBasis.kt)
- [MutationAdmission.kt](../../change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/MutationAdmission.kt)
- [LiveMutationAuthority.kt](../../change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/LiveMutationAuthority.kt)
- [HostedLiveSourceWriter.kt](../../change/intellij/src/main/kotlin/io/github/amichne/kast/change/intellij/HostedLiveSourceWriter.kt)
- [HostedChangePlanning.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangePlanning.kt)

## Read next

- [Receipts and recovery](receipts-and-recovery.md)
- [Hosted composition](../runtime/hosted-composition-and-publication.md)
