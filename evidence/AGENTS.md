<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: f40e0e1cdb87 -->

# evidence

## Purpose

Defines durable workspace/publication evidence and provides SQLite-backed stores for workspace generations, topology, and mutation recovery.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/WorkspacePublication.kt) - publication contract.
- [contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt](contract/src/main/kotlin/io/github/amichne/kast/evidence/contract/MutationRecoveryRecord.kt) - recovery evidence model.
- [sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteWorkspaceGenerationPublication.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteWorkspaceGenerationPublication.kt) - generation publication adapter.
- [sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt) - recovery journal.
- [sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologySnapshotStore.kt](sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/topology/SqliteTopologySnapshotStore.kt) - topology persistence.

## Subdirectories

- `contract` - storage-neutral publication and recovery types.
- `sqlite` - durable SQLite adapters and topology schema.

## Entry Points

- Gradle projects: `:evidence:contract`, `:evidence:sqlite`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/topology-evidence.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Establish the contract's authority and identity rules before inspecting SQL rows or transactions.
- For topology persistence, read `topology/contract` before `sqlite/topology`.
