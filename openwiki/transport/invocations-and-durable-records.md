---
type: System Explanation
title: Invocation fences and durable thread records
description: Explains durable invocation admission, uncertain outcomes, bounded active
  work, private sharded records, and current thread ownership.
tags:
- kast
- transport
code_sources:
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationRecords.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/FileInvocationRecords.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadBindingDocuments.kt
- path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFenceTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFileStoreTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFilesTest.kt
- path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStoreTest.kt
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-0271b35b2c019ff7a8e64073
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt
  - id: openwiki-source-0fba11cce5c31a291e99097c
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadBindingDocuments.kt
  - id: openwiki-source-968860afe19521f91b88e27a
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/FileInvocationRecords.kt
  - id: openwiki-source-3e7891399699fdb419d42fca
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt
  - id: openwiki-source-415e705acd321c2eaef23a33
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationRecords.kt
  - id: openwiki-source-fdd04d06dee30d192ed4f4da
    resource: repo://app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Invocation fences and durable thread records

## Durable history and active capacity differ

`InvocationFence` separates durable invocation records from this process's bounded active set.
Identity selects a hashed key.
The admitted fingerprint identifies the invocation's exact content.
A new invocation needs both an absent durable record and available active capacity.

The pure `invocationTransition` owns legal record changes.
A new record starts at STARTED and settles once as COMPLETED or UNCERTAIN.
A repeated identity with different content returns INPUT_CONFLICT.
A completed identity returns ALREADY_COMPLETED.
An existing started or uncertain identity returns OUTCOME_UNCERTAIN.
These results prevent a lost response from becoming permission to repeat an effect.

The fence releases active capacity only after durable settlement succeeds.
A failed settlement remains a failure with its protected state intact.
Cancellation and recovery use explicit outcomes instead of inferred success.

## Address one private record

`FileInvocationRecords` reads only the addressed hash shard.
Historical records do not populate the daemon's active cache.
Each operation locks the admitted store, reads a bounded typed record, applies the pure transition, and writes the admitted result.
Layout versions and migrations remain explicit finite outcomes.

`PrivateRecordFiles` admits canonical paths, ownership, private modes, and regular file identity before effects.
It rejects noncanonical serialization and oversized records.
Writes force a temporary file before an atomic replacement and directory force.
A busy store, malformed document, and rejected store remain distinct failures.
These mechanisms protect record identity without a claim of successful semantic execution.

## Persist thread context without reviving old authority

Thread records preserve thread identity, catalog digest, working directory, workspace identity, and installation owner.
Historical shape admission does not treat an old path as current filesystem authority.
Current binding admission reconstructs the constrained values and checks workspace identity.
Legacy migrated bindings remain `new_conversation_required` instead of automatically regaining current ownership.

`FileThreadCatalogStore` owns durable thread records and bounded addressed reads.
Protocol admission still compares the current catalog and owner before reuse.
A valid stored JSON shape alone cannot authorize a tool invocation.
Focused tests exercise duplicate identity, conflicts, interrupted history, migration, private file rejection, and thread admission.

## Focused tests

- [InvocationFenceTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFenceTest.kt)
- [InvocationFileStoreTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFileStoreTest.kt)
- [PrivateRecordFilesTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFilesTest.kt)
- [FileThreadCatalogStoreTest.kt](../../app-server/src/test/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStoreTest.kt)

## Source owners

- [InvocationFence.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationFence.kt)
- [InvocationRecords.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/InvocationRecords.kt)
- [FileInvocationRecords.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/runtime/FileInvocationRecords.kt)
- [PrivateRecordFiles.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/storage/PrivateRecordFiles.kt)
- [ThreadBindingDocuments.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/ThreadBindingDocuments.kt)
- [FileThreadCatalogStore.kt](../../app-server/src/main/kotlin/io/github/amichne/kast/appserver/protocol/FileThreadCatalogStore.kt)

## Read next

- [Coordinator lanes](coordinator-and-workspace-preparation.md)
- [Codex projection](codex-hosts-and-framing.md)
- [Mutation recovery](../changes/receipts-and-recovery.md)
