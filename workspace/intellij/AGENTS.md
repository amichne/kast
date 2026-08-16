# Workspace IntelliJ adapter module guide

`:workspace:intellij` owns live IntelliJ VFS refresh and Gradle import initiation for one admitted
workspace transition. It owns no transition state machine, persistence, server, mutation, or
aggregate backend.

## Invariants

- Live `Project`, VFS, and External System objects remain inside adapter calls.
- Gradle import and recursive VFS refresh are explicit transition effects, never ordinary reads.
- The adapter consumes only workspace contracts and retains no live object across calls.
- No duplicate refresh/import implementation or fallback may remain in the legacy host.

## Verification ladder

1. Run `./gradlew :workspace:intellij:test`.
2. Run the indexer transition runtime and event-driven integration tests.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
