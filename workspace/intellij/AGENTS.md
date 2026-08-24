# Workspace IntelliJ adapter module guide

`:workspace:intellij` owns live IntelliJ VFS refresh and Gradle import initiation for one admitted
workspace transition. It owns no transition state machine, persistence, server, mutation, or
aggregate backend.

`src/main/kotlin/io/github/amichne/kast/workspace/intellij/provenance/` owns the Gradle Tooling
model, resolver bridge, and installed source-root provenance capture. The parent package owns the
remaining workspace lifecycle and semantic identity boundaries.

## Invariants

- Live `Project`, VFS, and External System objects remain inside adapter calls.
- Gradle import and recursive VFS refresh are explicit transition effects, never ordinary reads.
- Installed bootstrap observes the project-open Gradle import when the exact root is already
  linked, initiates a link only when it is unlinked, installs the admitted project and Gradle JVMs
  in the project-open `beforeOpen` boundary before configurators can sync, and requires a
  continuous smart, unindexed-scanner-idle interval before capturing identity.
- Exact-workspace import tasks form one observer cohort. Each admitted ID remains until its own
  terminal callback, failure or cancellation is retained across the cohort, and success publishes
  only after every admitted task terminates. ID-only terminal callbacks require prior path-aware
  admission; a path-aware terminal without a start may publish only while the cohort is empty.
- Imported-module materialization treats external-project lookup failures and malformed external
  project paths as a closed model failure; it never guesses a project structure.
- Source-root provenance comes from Gradle Tooling API producer evidence captured before IntelliJ
  content-root projection. An ordinary `SOURCE` entry is not authored evidence; missing or
  conflicting producer evidence remains typed unknown and blocks workspace publication.
- Source-root path names never classify authored or generated provenance.
- The adapter consumes only workspace contracts and retains no live object across calls.
- No duplicate refresh/import implementation or fallback may remain in the legacy host.

## Verification ladder

1. Run `./gradlew :workspace:intellij:test`.
2. Run the indexer transition runtime and event-driven integration tests.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
