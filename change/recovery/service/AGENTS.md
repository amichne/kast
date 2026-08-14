# Change recovery service guide

`:change:recovery:service` coordinates exact approved-plan recovery preparation. It owns no
filesystem, JDBC, IntelliJ, workspace-transition, source-write, or rollback effect.

## Invariants

- Accept only a typed approved journal record and a matching revalidated add-declaration.
- Prepare the physical durable artifact before advancing the journal to `RECOVERY_PREPARED`.
- A rejected admission or physical preparation performs no journal transition.
- Return a stronger preparation proof only when revalidation, durable artifact, and journal record
  agree exactly.
- Every expected failure is a closed typed result and proves no source mutation began.

## Verification

Run `./gradlew :change:recovery:service:test`.
