# CLI runtime lifecycle tests

This directory owns layout-grouped tests for exact-root runtime lifecycle and process-session
behavior. The Kotlin package remains `io.github.amichne.kast.cli` so the tests retain friend access
to the CLI runtime boundaries.

- Keep this split layout-only: runtime behavior remains owned by the production CLI package.
- Preserve exact endpoint ownership, persistent-state retention, finite startup failures, and
  launchd observation distinctions.
- Run `./gradlew :cli:test --tests '*RuntimeLifecycleTest' --tests '*RuntimeProcessSessionTest'`
  after changing these tests or their production subjects.
