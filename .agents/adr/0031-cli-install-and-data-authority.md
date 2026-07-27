# ADR 0031: CLI installation and data authority

Status: Accepted

Date: 2026-07-25

## Reason this record remains

Kast spans a CLI, semantic backends, an IDEA plugin, and an external Codex
plugin. They must not independently choose installation or workspace data
paths.

## Decision

The active Kast CLI receipt is the sole source of truth for installation
identity and resolved state roots. `kast setup --source <bundle>` is the sole
persistent installation operation. It validates a complete bundle, stages it,
switches the active release atomically, verifies the new CLI, and restores the
previous verified release on failure.

All backends and plugins derive Kast data paths from the active CLI receipt.
The CLI-resolved workspace data directory is canonical for configuration,
runtime descriptors, source indexes, graph data, and other workspace state.
No plugin, backend, environment variable, or legacy default may establish a
parallel path authority. If the receipt is unavailable, consumers use only the
CLI's documented fallback layout under the same install root.

The external `amichne/kast-marketplace` repository owns Codex plugin packaging
and presentation. Its commands delegate to the active CLI and carry no
installation, runtime, index, graph, or database authority. Kast does not
generate repository guidance or workspace skills.

## Source and proof

- `cli-rs/src/configuration/manifest.rs`
- `cli-rs/src/configuration/config/`
- `cli-rs/src/operations/install/`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`
- `.github/scripts/install/test-setup-contract.sh`
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/config/WorkspacePathsTest.kt`

Any new installer, path derivation, database location, or state owner must
replace this decision explicitly; compatibility aliases do not create a second
authority.
