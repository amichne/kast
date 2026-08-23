# IntelliJ change writer guide

`:change:intellij` owns the sole clean-slate semantic source-write and source-rollback effects.
Live IntelliJ values exist only during one observation, write, or rollback call.

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
- The command performs only the authority's exact typed transformations and commits only the
  admitted target document. AddDeclaration distinguishes typed class-body and sibling insertions;
  RenameSymbol replacement and whole ReplaceDeclaration replacement likewise do not expose a
  public raw-edit primitive.
- ReplaceDeclaration read preparation matches exactly one declaration PSI range and preimage and
  parses the typed replacement as one Kotlin declaration before command entry.
- Applied-write durability is recorded after the in-memory command and before physical save.
- AddFile stages a syntax-valid whole-file postimage in memory, records applied-write durability,
  then creates exactly one physical file. Its rollback deletes only the unchanged exact postimage.
- A rejected durability barrier restores the in-memory preimage and performs no save.
- Save or observation faults return recovery-required data; rollback overwrites only the exact
  admitted postimage or accepts an already-restored exact preimage.

## Verification

Run `./gradlew :change:intellij:test`.
