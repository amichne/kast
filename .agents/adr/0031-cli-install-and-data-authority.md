# ADR 0031: CLI installation and data authority

Status: Accepted

Date: 2026-07-25

## Reason this record remains

Kast spans a CLI, one semantic indexer, and external agent harness resources.
They must not independently choose installation or workspace data paths.

## Decision

The active Kast CLI receipt is the sole source of truth for installation
identity and resolved state roots. `install.sh` is the supported bootstrap and
update interface. It delegates to `libexec/kastctl setup --source <bundle>`,
the sole persistent installation operation. Setup validates a complete bundle,
stages it, switches the active release atomically, verifies the new CLI, and
restores the previous verified release on failure. The public `kast` interface
does not expose installation commands.

The indexer and harness resources derive Kast data paths from the active CLI
receipt. The CLI-resolved workspace data directory is canonical for
configuration, runtime descriptors, source indexes, graph data, and other
workspace state. No indexer, harness resource, environment variable, or legacy
default may establish a parallel path authority.

Kast embeds its Codex, Claude, and Copilot resources and installs only the
harnesses selected by `install.sh`. No marketplace or GitHub Action owns
installation.

## Source and proof

- `cli-rs/src/configuration/manifest.rs`
- `cli-rs/src/configuration/config/`
- `cli-rs/src/operations/install/`
- `install.sh`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspacePaths.kt`
- `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceIdentity.kt`

Any new installer, path derivation, database location, or state owner must
replace this decision explicitly.
