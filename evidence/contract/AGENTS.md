# Evidence contract module guide

`:evidence:contract` owns detached publication values and opaque transaction capabilities. It
contains no persistence implementation and performs no I/O.

## Invariants

- A publication retains one verified workspace identity and one evidence generation.
- Open and prepared publication capabilities are opaque and owner-specific.
- Only a prepared capability can be committed; invalidation before and after commit remain
  distinct outcomes.
- Mutation recovery evidence binds one plan identity to deterministic exact pre-write images,
  applied write sets, and a digest-chained closed state. The contract owns no JDBC or rollback
  effect.
- Do not import JDBC, SQLite, IntelliJ, Gradle, filesystem, transport, server, or legacy hosts.

## Verification ladder

1. Run `./gradlew :evidence:contract:test`.
2. Run `./gradlew :evidence:sqlite:test`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
