---
type: Kotlin Module Group
title: Durable change evidence
description: SQLite retains immutable live plans, attempt history, recovery journals and verified receipts; semantic indexes remain in IDEA.
resource: file://evidence
tags: [kotlin, topology, evidence, sqlite]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: evidence/contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt
    symbols: [WorkspacePublicationAuthority]
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangePlanStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteLiveChangeReceiptStore.kt
  - path: evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
---

# Durable change evidence

The shipped SQLite adapter stores live change plans, permanent application-attempt claims, mutation recovery journals and historical verified receipts. These records support approval, replay prevention and recovery. They do not constitute a semantic index or grant current source-write authority.

`SqliteHostedChangeStores.open` admits one exact mutation-database location, initializes one connection source and retains that capability in the plan, journal and receipt stores. JDBC connections remain scoped to explicit effects. Path admission rejects aliases, symlinks and non-file targets with finite database causes. Plan and receipt schema failures remain distinct variants.

`HostedChangeFailure` preserves state-root, location, database, plan, receipt and plan-lookup failures through endpoint serialization and bounded diagnostic observations. Missing plans, corrupt rows, incompatible versions and I/O rejection remain distinct. Successful open and lookup stages emit completion evidence without paths or source payloads.

The former topology contract/build/service/IntelliJ modules and SQLite topology/publication adapters are retired. The active Gradle graph has no topology build, publication or source-root-refresh owner. Relation and traversal reads use the current IDEA index through the live read authority. Historical published-evidence contracts remain interpretable; the plugin never fabricates a generation from a live epoch.

Legacy change tables are preserved when opening the mutation database. Store tests verify that recovery-compatible records survive and that new shared-store initialization creates only mutation tables. Read [change lifecycle](../flows/change-lifecycle.md) before altering persistence semantics.
