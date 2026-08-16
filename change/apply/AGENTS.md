# Change apply service guide

`:change:apply` owns KCS-017's host-neutral admission and application workflow for one closed
semantic `ChangePlan`. It revalidates exact current state, makes pre-write recovery evidence
durable, and only then issues `MutationAuthority` to an injected source writer.

## Dependency boundary

- Production depends only on `:change:contract`, `:change:recovery`, and `:evidence:contract`.
- Do not import IntelliJ, filesystem, JDBC, SQLite, workspace services, verification, runtime,
  transport, legacy apply, journal, or callback types.
- Source observation, writing, and rollback are explicit narrow ports. This module performs none
  of those effects itself.

## Invariants

- Admission revalidates exact root, generation, source state, content, source-root ownership,
  authored provenance, writability, exact planned transformations, and requested write scope.
- Source preconditions are closed: existing-file plans require the exact content identity, while
  AddFile requires exact physical absence and parent-derived creation access.
- `MutationAuthority` has no public constructor and exists only after exact pre-write recovery
  evidence is durable.
- A source writer receives only the admitted source, typed transformations, exact preimage, and
  exact postimage carried by `MutationAuthority`.
- Applied source state becomes `AppliedUnverified`; this module cannot construct verified success.
- Any post-durability write fault resolves through exact rollback or `RecoveryRequired`.

## Verification

Run `./gradlew :change:apply:test`.
