# Workspace inventory instructions

This directory owns the crate-internal, exact-root inventory used by
`kast agent workspace-files` and Gradle DSL consumers.

- Read the source index through `config::workspace_database_path`.
- Never enumerate filesystem or Git paths as source candidates.
- Never admit `.kts` from the `.kt` source index.
- Do not apply public filters or result limits while collecting the internal
  inventory.
- Treat legacy module/source-set strings and nullable parser output as
  unproven evidence. Proven package and Gradle ownership use their typed schema
  states.
- Preserve generation, completeness, containment, ownership, and drift
  evidence when composing lanes.
