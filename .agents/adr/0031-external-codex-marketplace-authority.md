# ADR 0031: External Codex marketplace authority

Status: Accepted

Date: 2026-07-21

This record supersedes ADR 0026, ADR 0030, and the agent-guidance ownership
portions of ADR 0002, ADR 0006, ADR 0008, and ADR 0027.

## Decision

The public `amichne/kast-marketplace` repository is the sole source and
distribution authority for Kast's Codex plugin. Its marketplace name is
`kast`, its install identity is `kast@kast`, and workstation reconciliation
tracks the repository's `main` branch.

That repository owns the plugin manifest, routing skill, hooks, launcher, and
plugin presentation assets. The Kast repository owns only the CLI hook bridge
invoked by the launcher. It does not embed, generate, package, release, hash,
or publish a Codex marketplace or standalone Kast skill.

`kast machine reconcile` removes any selected `kast@kast` installation and
registered `kast` marketplace, registers
`amichne/kast-marketplace --ref main`, and installs `kast@kast`. This is an
explicit fast-forward mechanism: marketplace and CLI releases are independent,
and no shared artifact digest or exact version equality is required.

Machine receipts authorize only the CLI and IDEA plugin digests. Schema 3
receipts contain only those component digests. Readers accept legacy schema 1
and 2 receipts but ignore obsolete `skillSha256` and `codexSha256` fields.

Kast does not write `AGENTS.md`, `AGENTS.local.md`, or workspace skill files.
`kast setup` is a tombstone that routes users to the Codex plugin. Agent
readiness checks the selected CLI and semantic backend, not plugin skill
dialects or guidance file contents.

This is a clean break. Kast does not detect, migrate, rewrite, or delete prior
managed guidance regions or workspace skills. Existing files remain ordinary
workspace content and may appear in user-reviewed Git diffs.

## Source ownership

| Contract | Source of truth |
| --- | --- |
| Marketplace and Codex plugin | `https://github.com/amichne/kast-marketplace` |
| Machine activation and reconciliation | `cli-rs/src/machine.rs` |
| Codex hook behavior | `cli-rs/src/codex/hook.rs` |
| IDEA receipt loading | `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/MacosMachineManifestLoader.kt` |

## Validation

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked \
  --test machine_authority_smoke \
  --test agent_setup_smoke \
  --test agent_readiness_smoke
./gradlew :backend-idea:test \
  --tests io.github.amichne.kast.idea.MacosMachineManifestLoaderTest \
  --tests io.github.amichne.kast.idea.KastProjectOpenProfileAutoInitTest \
  --no-daemon
(cd ../kast-marketplace && ./scripts/validate)
```
