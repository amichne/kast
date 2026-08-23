# Relation IntelliJ adapter module guide

`:relation:intellij` owns request-local K2 confirmation and bounded native enumeration for one-hop
relation reads. It does not own contracts, traversal, persistence, transport, workspace import,
workspace transitions, derived graph construction, or mutation.

## Invariants

- Recompile only the exact selector's retained scope and revalidate the subject through K2 before
  enumeration.
- Native indexes may enumerate candidates, but K2 analysis is the only authority that admits an
  endpoint or semantic relation.
- Each closed meaning has an explicit K2 confirmation path. A generic kind/direction composition
  is prohibited.
- Live `Project`, PSI, VFS, search-scope, query, and K2 session values remain request-local.
- Apply result, byte, work, and elapsed bounds during enumeration. Every halt, unresolved item,
  unsupported shape, dumb-mode transition, or provider failure is qualified and resumable.
- Only limitation-free terminal enumeration reports complete coverage or exact absence.

## Verification ladder

1. Run `./gradlew :relation:intellij:test --tests '*RelationReadTest'`.
2. Inspect changed Kotlin files and resolve K2 symbols through the exact-worktree IDEA MCP.
3. Build changed Kotlin files through IDEA.
4. Run `./gradlew :relation:intellij:test`.
5. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
