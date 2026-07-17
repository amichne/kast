# ADR 0002: Agent resource and workflow source of truth

Status: Accepted

Date: 2026-06-25

Supersession note: ADR 0005 and ADR 0006 supersede the public agent setup,
harness selection, Copilot package install, portable instruction install,
`kast agent tools`, `kast agent call`, raw command, and workflow CLI portions
of this ADR. This ADR remains authoritative only for manifest-backed trust
rules not replaced by ADR 0006. ADR 0024 adds an isolated local-development
authority whose receipt must prove the same resource-trust facts without
changing release authority.

This ADR records the current contract for agent-facing Kast resources. It
exists so future agents can preserve the source of truth for the packaged
skill, repo-local guidance, internal command catalog, and generated validation
artifacts without maintaining compatibility branches for stale binaries.

## Context

Kast exposes several authored sources that can look like independent products:
the plugin package source, packaged skill, command catalog, generated protocol
artifacts, and repo-local guidance installer. Older iterations used marker
files and script-level workflow helpers to decide whether installed copies were
current. That made drift easy to miss and created pressure to keep paths alive
only because older installed binaries might not understand the new shape.

The v1 contract needs one explicit rule: the active `kast` binary owns the
installed agent resources and supported command surface. If a repository has
stale, missing, or incompatible agent resources, the fix is to upgrade or
reinstall Kast and refresh from the active binary bundle. Do not add a
maintained path that exists only for older binaries.

## Decision

Kast will use manifest-backed resource records as the current source-of-truth
model.

| Surface | Source of truth | Installed or generated output | Verification |
| --- | --- | --- | --- |
| Plugin package source | `cli-rs/resources/plugin/` and `primitive-manifest.json` | generated plugin package artifacts consumed by release and LSP checks | `.github/scripts/test-kast-copilot-plugin.sh`, `.github/scripts/test-lsp-pivot-gates.sh` |
| Internal command catalog | `cli-rs/protocol/source/commands.json` | internal request schemas, samples, and LSP custom route metadata | `cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check`, `cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke` |
| Packaged skill | `cli-rs/resources/kast-skill/SKILL.md` | thin installed `kast` skill entrypoint only | CLI smoke tests and packaged content tests |
| Protocol maintenance evidence | `cli-rs/protocol/maintenance/` | routing and output-format evaluation reports | `.github/scripts/test-kast-routing-evals.sh`, packaged content tests |
| Repo-local agent guidance | `cli-rs/src/install/agent_guidance.rs` | selected context file with one managed `<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">` region | CLI smoke tests, docs content contract |
| Repo resource trust | `$HOME/.local/share/kast/install.json` | managed repo resource records with output checksums | `kast --output json ready`, verifier script |
| Local-development resource trust | `cli-rs/src/local_development/` plus the captured checkout's skill source and independently attested CLI/backend artifacts | one immutable local generation and its strict authority receipt | `.github/scripts/test-local-development-refresh-contract.sh` |

Marker files such as `.kast-version` and `.github/.kast-copilot-version` are
retired. They may be detected as stale state, but they are not trusted as a
current install signal.

## Hard requirements

Agent-facing changes must keep these requirements true:

- Repo-installed resources are recorded in `install.json` with kind, target,
  primitive version, source bundle checksum, output paths, and output checksums.
- `kast setup` writes repo-local guidance to the selected context file, adds
  local-only generated guidance to `.git/info/exclude` unless auto-exclude is
  disabled, and owns only the managed `<kast ...>` region.
- IDEA and headless project-open setup run the same repo-local guidance
  command by default for Gradle workspaces; `projectOpen.profileAutoInit =
  false` is the explicit opt-out.
- `kast ready` and the source-tree verifier fail closed on missing, stale, or
  tampered manifest-backed resources.
- `kast setup` installs only `SKILL.md` plus the managed guidance region;
  concise public guidance stays beside the skill, internal catalogs and
  generated request samples stay under `cli-rs/protocol/source/`, maintenance
  evaluations stay under `cli-rs/protocol/maintenance/`, and internal helper
  scripts stay outside the installable skill.
- Stale active-binary/resource combinations report incompatibility and require
  upgrade or reinstall. Do not add a compatibility helper just for older
  binaries.
- A local-development refresh records the canonical checkout snapshot and
  length-framed component hashes, requires matching CLI/backend artifact
  provenance, projects resources only into the explicit exact workspace, and
  fails closed when any effective resource differs from that generation.
- Local-development runtime state is keyed by source generation, and its
  installed skill and guidance route only through the receipt-owned local
  entrypoint.
- Local-development rollback and removal restore only receipt-owned resource
  state; they never mutate Homebrew or JetBrains release authority.

## Instruction topology

`AGENTS.md` files are part of this contract. They route future agents to the
right source before editing an installed or generated output. Generated
repo-local Kast guidance belongs in ignored `AGENTS.local.md`, not in the
authored root `AGENTS.md`.

| Instruction file                               | Scope                                                                       |
|------------------------------------------------|-----------------------------------------------------------------------------|
| `AGENTS.md`                                    | Repo-wide build, type-safety, generated-output, and decision-record routing |
| `.agents/AGENTS.md`                            | Agent-only docs and local agent tooling                                     |
| `.agents/adr/AGENTS.md`                        | Agent-only decision records                                                 |
| `cli-rs/AGENTS.md`                             | Rust CLI, installer, manifest, agent command, and resource bundling work    |
| `cli-rs/resources/plugin/AGENTS.md`            | Authored Copilot package source                                             |
| `cli-rs/resources/kast-skill/AGENTS.md`        | Thin provider-neutral packaged skill and public reference guidance          |
| `cli-rs/protocol/AGENTS.md`                    | Internal catalog, generated request fixtures, and maintenance evaluations   |
| `.github/AGENTS.md`                            | Authored GitHub automation vs generated Copilot package outputs             |
| `.agents/docs/AGENTS.md`                       | Published docs site source guidance kept out of the site                    |
| `AGENTS.local.md` (special case)               | Ignored repo-local Kast setup guidance generated by the active binary       |

Add a scoped `AGENTS.md` only when a subtree has a real local delta: different
commands, source ownership, generated-output rules, or validation gates. Do not
create directory commentary that repeats the parent.

## Change process

When an agent-facing package or command contract changes:

1. Edit the authored source first.
2. Update the nearest `AGENTS.md` if the edit changes source ownership,
   generated boundaries, or required validation.
3. Add or supersede an agent-only ADR when the change alters the product
   contract, supported workflow, or compatibility posture.
4. Regenerate derived artifacts or reinstall ignored package outputs only from
   the active binary/source bundle.
5. Run the narrowest source contract plus the package/docs contracts affected
   by the change.

The contract is intentionally upgrade-forward. If a stale installed resource or
older active binary cannot prove the current contract, the correct response is
to fail loudly, explain the incompatibility, and require upgrade or reinstall.

## Validation

Changes governed by this ADR normally require one or more of these commands:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

Run `./gradlew test` when a change touches Kotlin/JVM behavior, generated API
docs, LSP/backend contracts, or release readiness beyond the Rust CLI and
agent package surfaces.
