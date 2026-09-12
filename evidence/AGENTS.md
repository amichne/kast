<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: 415a0ae996a1 -->

# evidence

## Purpose

Retains mutation plans, receipts, and recovery evidence in workspace-bound SQLite stores. A separate topology SQLite adapter retains complete graph snapshots for upcoming work; workspace publication implementation remains retired.

## Key Files

- [HostedWorkspaceStateLocation.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/HostedWorkspaceStateLocation.kt) - admitted workspace-bound state location.
- [MutationRecoveryRecord.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt) - recovery evidence model.
- [SqliteHostedChangeStores.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStores.kt) - one admitted database authority for all mutation stores.
- [HostedWorkspaceStateFiles.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/HostedWorkspaceStateFiles.kt) - physical path admission.
- [SqliteMutationRecoveryJournal.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt) - recovery journal.
- [SqliteHostedChangeStoresTest.kt](sqlite/src/test/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteHostedChangeStoresTest.kt) - restart, schema and path rejection evidence.

## Subdirectories

- `contract` - storage-neutral recovery types and retained historical publication contracts.
- `sqlite` - mutation persistence and strict database admission.
- `topology-sqlite` - retained topology publication, snapshot storage, and relation reads; excluded from shipped runtimes.

## Entry Points

- Gradle projects: `:evidence:contract`, `:evidence:sqlite`, `:evidence:topology-sqlite`.

## Navigation Hints

- Start with [durable change evidence](../knowledge/modules/topology-evidence.md).
- Establish authority and identity rules before inspecting SQL rows. Existing incompatible databases must fail closed without replacement.
