# IntelliJ backend agent guide

`backend-intellij` is the only unit that should talk to IntelliJ Platform,
PSI, and Kotlin plugin APIs.

## Ownership

Treat this module as the IntelliJ-specific implementation boundary.

- Keep IntelliJ-specific behavior here: PSI-backed symbol resolution,
  references, rename planning, diagnostics, and plugin lifecycle wiring.
- Respect IntelliJ threading and document rules. Read work belongs in smart-
  mode read actions, and mutations must remain serialized through the existing
  write path with document commit and save.
- Keep `capabilities()` honest. This backend currently advertises symbol
  resolution, references, diagnostics, rename, and apply-edits, but not call
  hierarchy.
- Preserve project-scoped startup and shutdown behavior in
  `KastProjectActivity` and `KastProjectService`. The plugin must start once
  per project and tear down cleanly.
- Prefer extending the PSI-backed implementation over adding fallback text
  heuristics when symbol identity matters.

## Verification

Prefer a real module build because packaging and plugin wiring matter here.

- Run `./gradlew :backend-intellij:build`.
- If you change capability behavior, also run the server tests or add focused
  plugin tests for the new surface.
