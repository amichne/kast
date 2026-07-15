# Workspace inventory ownership

This directory contains the crate-internal, uncapped, exact-root workspace
inventory assembled through direct source-index reads for `kast agent
workspace-files` and Gradle DSL consumers. Under
`.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`, it
must migrate behind typed backend APIs. Do not add new direct database lanes or
consumers here. Until that migration lands, changes are limited to correctness
fixes required to preserve the existing evidence contract or to remove the
direct reader. It reads `.kt` source-index candidates with generation, module
progress, and pending-update evidence; composes set-valued backend and indexed
Gradle owners; uses deepest-existing-ancestor containment; preserves backend
page coverage; and carries Git annotations without letting any one lane
overstate exactness.

The source index is read-only here. Never enumerate filesystem or Git paths as
source candidates, never admit `.kts` from the `.kt` source index, and never
apply a public filter or result limit while collecting the internal inventory.
Treat legacy `module_path` and `source_set` strings and nullable parser output
as unproven evidence only. Build-qualified Gradle ownership, structured source
sets, and package identity require their dedicated proven schema states.

New workspace-inventory contracts belong in `analysis-api`; the active backend
serves them through `analysis-server`. Preserve generation, completeness,
containment, ownership, and drift evidence during migration.
