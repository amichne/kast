# Architecture policy definition

This directory owns the canonical module-policy declarations.

## Local scope

- Keep every active Gradle project and every planned delivery module represented exactly once.
- `workspace:intellij-read`, `runtime:ide-read`, and `ide-plugin` remain `PLANNED` and
  `IDE_READ_ONLY` until the delivery task that materializes each module changes its lifecycle.
- IDE-read modules may depend only on declared inward contracts or another IDE-read module and may
  allow only the generic IntelliJ platform read effect.
- Regenerate `gradle/architecture/kast-architecture-policy.json` after every policy change.
