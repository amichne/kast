---
title: Changelog
description: Release history and migration notes.
icon: lucide/file-text
---

# Changelog

## v1.0.0

Initial public release. Kast is a JSON-RPC analysis daemon that gives AI agents
IDE-grade Kotlin intelligence — symbol resolution, call/type hierarchy,
references, diagnostics, rename, and more — over a stable, tool-friendly
protocol.

### Backends

Two fully interoperable backends, both speaking the same JSON-RPC protocol
over Unix domain sockets:

- **Standalone** — GraalVM native-image launcher with JVM daemon fallback.
  Ships as a self-contained zip produced by `./kast.sh build cli`. Fast startup
  (~50 ms native) with no IDE dependency.
- **IntelliJ plugin** — Delegates to the open IDEA instance for analysis.
  Shares the IDE's already-warm PSI and K2 compiler, so results are
  semantically identical to what IntelliJ shows. Installed as a standard
  plugin zip.

### Analysis surface

All operations are available on both backends:

| Operation | Method |
|-----------|--------|
| Resolve symbol at position | `raw/resolve` |
| Find all references | `raw/references` |
| Incoming / outgoing call hierarchy | `raw/call-hierarchy` |
| Type hierarchy (supertypes & subtypes) | `raw/type-hierarchy` |
| File outline (declarations) | `raw/file-outline` |
| Workspace-wide symbol search | `raw/workspace-symbol` |
| Workspace content search | `raw/workspace-search` |
| Workspace files and source roots | `raw/workspace-files` |
| Compiler diagnostics | `raw/diagnostics` |
| Rename symbol (plan + apply) | `raw/rename` |
| Apply arbitrary edits | `raw/apply-edits` |
| Optimize imports | `raw/optimize-imports` |
| Code completions | `raw/completions` |
| Implementations of interface/abstract | `raw/implementations` |
| Available code actions | `raw/code-actions` |

### Source index

The standalone backend maintains a SQLite-backed source index that survives
restarts and enables sub-millisecond workspace-symbol lookup:

- **Phase 1** — file and declaration indexing, populated on workspace open.
- **Phase 2** — full reference resolution, built incrementally in the
  background after Phase 1 completes.
- **Incremental reindex** — file-level delta updates keep the index current
  as files change, avoiding full rebuilds.
- **Path interning** — compact on-disk representation with reconciled pending
  updates for write consistency.

### Completeness metadata

Every result that may be truncated carries explicit metadata so agents know
when to paginate or bound their queries:

- `searchScope.exhaustive: boolean` on every `raw/references` result.
- `stats` and `truncation` on every `raw/call-hierarchy` node.
- SHA-256 conflict detection on `raw/rename` and `raw/apply-edits` — mutations are
  rejected if the file has changed since the plan was computed.

### Distribution

- **`kast install skill`** — bundles the agent skill (`SKILL.md`), generated
  `commands.json` catalog, quickstart reference, resolver/bootstrap scripts,
  and evaluation assets into `.agents/skills/kast/` inside any target
  repository. One command, no internet access required after the initial
  install.
- **Portable CLI zip** — `./kast.sh build cli` produces
  `kast-cli-<version>-portable.zip`, a self-contained bundle that includes the
  native launcher, JVM fallback, and all runtime libs.
- **Portable backend zip** — `./kast.sh build backend --shrink` produces the
  stripped standalone backend zip used by CI and remote deployments.
- **OpenAPI spec** — `docs/openapi.yaml` is generated from the analysis API
  model registry and kept in sync with every build.

### CI & release

- GitHub Actions release workflow triggered by `workflow_dispatch` with
  `release_type: major | minor | patch | beta`. The workflow auto-increments
  the semver tag, builds native launchers on Linux (x64) and macOS (ARM64),
  packages the IntelliJ plugin and headless agent bundle, uploads aggregate
  SHA-256 checksums, and publishes a GitHub release with combined build
  provenance. The workflow verifies every shipped asset against `SHA256SUMS`
  and `build-provenance.json` before publication. Beta tags publish as GitHub
  prereleases; stable tags publish the verified GitHub release before updating
  and watching the Homebrew tap.
- Upstream sync workflow keeps the standalone backend's bundled IntelliJ
  distribution current.
- Copilot setup steps pre-warm Gradle caches and Java 21 for GitHub Copilot
  coding agents.

### Workspace discovery

The standalone backend auto-discovers Gradle projects by walking the
filesystem from the workspace root, resolving `settings.gradle.kts` and
multi-project structures without any configuration.

### Interactive CLI (`kast demo`)

A Kotter-based interactive terminal UI for exploring symbol graphs:

- Tree-shaped call-hierarchy walker with depth limiting.
- FQCN picker and `fzf`-backed menu for symbol selection.
- Color-coded phase bars, operation rails, and module cells.
- Dual-pane layout with fixture replay support for demos.
