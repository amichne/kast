# CLI runtime lifecycle tests

This directory owns layout-grouped tests for exact-root runtime lifecycle, process-session, and
native UDS transport behavior. The Kotlin package remains `io.github.amichne.kast.cli` so the tests
retain friend access to the CLI runtime boundaries.

- Keep this split layout-only: runtime behavior remains owned by the production CLI package.
- Preserve exact endpoint ownership, persistent-state retention, finite startup failures, and
  launchd observation distinctions.
- Keep IDE-only installed demand tests separate from explicit legacy lifecycle/process fixtures;
  they must not grant the IDE stop, cleanup, acquisition, or process-start authority.
- Run `./gradlew :cli:test --tests '*RuntimeLifecycleTest' --tests '*RuntimeProcessSessionTest'`
  and `./gradlew :cli:nativeTest --tests '*CliNativeTransportTest'` after changing these tests or
  their production subjects.
