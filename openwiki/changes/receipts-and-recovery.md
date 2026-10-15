---
type: System Explanation
title: Mutation receipts and recovery
description: Explains mutation application claims, durable preimages, post-effect
  evidence, receipts, and recovery after interruption.
tags:
- kast
- changes
code_sources:
- path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/LiveAddDeclarationVerification.kt
- path: change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt
- path: change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt
- path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt
- path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
- path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt
- path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeCoordinator.kt
- path: evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStoresTest.kt
- path: evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournalTest.kt
- path: evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStoreTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-8425182c27f5ffccaa2cb5cc
    resource: repo://change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt
  - id: openwiki-source-6bd27197bb1b67f8517612f7
    resource: repo://change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/LiveAddDeclarationVerification.kt
  - id: openwiki-source-e3dde77768b520a5c0b7ea7e
    resource: repo://evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt
  - id: openwiki-source-6f467c39d3cd5a49a04bafa6
    resource: repo://evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Mutation receipts and recovery

## One durable mutation history

`SqliteHostedChangeStores.open` admits one mutation database for the plan, journal, and receipt stores.
The location proof carries the selected workspace into the persistence adapter.
Path, schema, plan codec, and receipt failures remain distinct outcomes.
The adapter does not replace an incompatible database to manufacture an empty success state.

Stored plans keep their exact identity and application history.
The permanent application claim prevents a second write with the same plan after a restart or interrupted response.
A missing response does not erase the recorded attempt.
The coordinator must inspect the durable history before it decides which recovery operation remains legal.

## Receipts need post-effect evidence

Live verification reobserves the current original owner, imported model, saved postimage, and compiler anchor.
`LiveAddDeclarationVerificationReplay` repeats the retained semantic requests with their original budgets and scopes.
Relations, traversals, and diagnostics remain independent evidence inputs.
A matching source hash alone does not discharge semantic obligations.

Published mutation verification uses a separate admitted request that binds the plan and applied state.
That path can establish a successor publication through its existing proof owners.
It does not convert a live epoch into a published generation.
The resulting receipt records what the exact operation established, including qualifications and failures.

## Recovery transitions

`AddDeclarationRecoveryService.prepare` persists the exact preimage before the source effect.
The journal distinguishes durable preparation, durable applied writes, rollback, and recovery-needed states.
A pre-write record does not prove that no write occurred during an interruption.
Independent source examination can establish the known postimage without a repeated mutation.

Rollback consumes the exact persisted write set through `AddDeclarationRollbackPort`.
An unavailable target, diverged content, or rejected write remains a finite failure.
The service keeps undurable or terminal-persistence failures explicit when it cannot persist a stronger recovery result.
The hosted coordinator owns the order of write, verification, receipt, recovery, and final response.

SQLite restart tests establish durable storage behavior under owned files.
Native document and installed-host evidence remain separate from those persistence tests.

## Focused tests

- [SqliteHostedChangeStoresTest.kt](../../evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStoresTest.kt)
- [SqliteMutationRecoveryJournalTest.kt](../../evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournalTest.kt)
- [SqliteLiveChangePlanStoreTest.kt](../../evidence/sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStoreTest.kt)

## Source owners

- [LiveAddDeclarationVerification.kt](../../change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/LiveAddDeclarationVerification.kt)
- [VerifiedMutationService.kt](../../change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt)
- [AddDeclarationRecoveryService.kt](../../change/recovery/src/main/kotlin/io/github/amichne/kast/change/recovery/AddDeclarationRecoveryService.kt)
- [MutationRecoveryRecord.kt](../../evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt)
- [SqliteHostedChangeStores.kt](../../evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt)
- [SqliteLiveChangePlanStore.kt](../../evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt)
- [SqliteLiveChangeReceiptStore.kt](../../evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt)
- [HostedChangeCoordinator.kt](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeCoordinator.kt)

## Read next

- [Planning and writes](planning-and-write-admission.md)
- [Installation shutdown](../distribution/upgrades-shutdown-and-reset.md)
- [Historical storage](../topology/historical-capture-and-storage.md)
