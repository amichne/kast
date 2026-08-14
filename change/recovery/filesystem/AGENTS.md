# Filesystem change recovery guide

`:change:recovery:filesystem` is the physical adapter that writes an exact durable before image to
an admitted recovery directory. It has no source mutation, planning, journal, workspace, IntelliJ,
transport, or rollback orchestration authority.

## Invariants

- Admit one normalized absolute, real, non-symlink recovery directory before use.
- Artifact names derive only from canonical PlanId; caller paths never select an artifact.
- Create an artifact once, force its bytes and directory entry, and treat an identical artifact as
  idempotent success.
- A symlink, non-regular artifact, or mismatched existing bytes fails closed and is never replaced.
- Preparation never reads or writes the planned source target.

## Verification

Run `./gradlew :change:recovery:filesystem:test`.
