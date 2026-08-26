# Architecture policy definition

This directory owns the canonical module-policy declarations.

## Local scope

- Keep every active Gradle project and every planned delivery module represented exactly once.
- `ide-plugin` and `workspace:intellij-read` are active `IDE_READ_ONLY` modules.
  `runtime:ide-read` remains `PLANNED` until its delivery task materializes it.
- Lifecycle changes must match one monotonic `IdeReadFirewallStage`; activation cannot skip a
  predecessor module or return an active module to planned state.
- IDE-read modules may depend only on declared inward contracts or another IDE-read module and may
  allow only the generic IntelliJ platform read effect.
- Regenerate `gradle/architecture/kast-architecture-policy.json` after every policy change.
