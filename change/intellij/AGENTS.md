# IntelliJ change writer guide

`:change:intellij` owns KCS-017's sole clean-slate source-write and source-rollback effects. Live
IntelliJ values exist only during one observation, write, or rollback call.

## Dependency boundary

- Production consumes `:change:apply` authority and ports plus the exact change and recovery
  contracts required at the physical boundary.
- Do not import planning services, verification, workspace transitions, JDBC, SQLite, runtime,
  transport, legacy apply services, or generic edit abstractions.
- A normal source write is callable only with `MutationAuthority`. Rollback additionally requires
  its matching durable applied-write record.

## Invariants

- Read preparation proves a valid writable Kotlin target, exact document preimage, and exact
  compiler-grounded declaration range before command entry.
- The command performs only the authority's one insertion and commits only the target document.
- Applied-write durability is recorded after the in-memory command and before physical save.
- A rejected durability barrier restores the in-memory preimage and performs no save.
- Save or observation faults return recovery-required data; rollback overwrites only the exact
  admitted postimage or accepts an already-restored exact preimage.

## Verification

Run `./gradlew :change:intellij:test`.
