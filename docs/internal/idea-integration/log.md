# IDEA Integration Knowledge Log

## 2026-07-28

### Initial complete flow map

- Added a hidden bundle for load and bootstrap, Gradle sync, Kotlin indexing,
  semantic graph refresh and query, and runtime shutdown.
- Recorded the global workspace-state, single startup owner, model-backed
  inventory, generation pinning, and close-order decisions.
- Linked every concept to current Kotlin, Java, Rust, or SQLite source evidence.
- Kept the bundle out of public documentation navigation.
- Passed the strict knowledge-bundle check and a clean Zensical site build.

### Startup, graph, and shutdown hardening

- Documented global configuration and workspace-data ownership without
  project-local `.kast` state.
- Stabilized Git repository state on the common-directory hash, with bounded,
  fail-closed migration from one exact legacy remote-keyed worktree leaf.
- Required global compatibility metadata before every backend start while
  keeping optional profile setup distinct.
- Added the exact-root Gradle join-or-refresh decision and completion boundary.
- Retained pending, ready, and failed Gradle admission across backend restart.
- Added PSI and source-index generation compare-and-set behavior.
- Distinguished bounded SQL node and neighbor reads from full topology and
  community materialization.
- Collapsed parallel analytics links in SQL while retaining occurrence counts
  and total weights; direct neighbor reads remain typed.
- Reused one complete Gradle model snapshot for inventory and provenance, and
  preserved the committed index when model evidence is incomplete.
- Extended shutdown through dispatcher cancellation, quiescence, and complete
  off-event-thread cleanup.
- Added stateful asynchronous stop and dynamic-unload veto until every backend
  drain completes.
