# Workspace inventory ownership

This directory owns the crate-internal, uncapped, exact-root workspace
inventory assembled for `kast agent workspace-files` and Gradle DSL consumers.
It reads `.kt` source-index candidates with generation, module progress, and
pending-update evidence; composes set-valued backend and indexed Gradle owners;
uses deepest-existing-ancestor containment; preserves backend page coverage;
and carries Git annotations without letting any one lane overstate exactness.

The source index is read-only here. Never enumerate filesystem or Git paths as
source candidates, never admit `.kts` from the `.kt` source index, and never
apply a public filter or result limit while collecting the internal inventory.
Treat legacy `module_path` and `source_set` strings and nullable parser output
as unproven evidence only. Build-qualified Gradle ownership, structured source
sets, and package identity require their dedicated proven schema states.
