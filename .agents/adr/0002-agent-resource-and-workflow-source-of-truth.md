# ADR 0002: Agent resource and workflow source of truth

Status: Accepted

Date: 2026-06-25

Supersession note: ADR 0005 and ADR 0006 supersede the public agent setup,
harness selection, Copilot package install, portable instruction install,
`kast agent tools`, `kast agent call`, raw command, and workflow CLI portions
of this ADR. This ADR remains authoritative only for manifest-backed trust
rules not replaced by ADR 0006. ADR 0024 adds an isolated local-development
authority whose receipt must prove the same resource-trust facts without
changing release authority. ADR 0027 supersedes the readiness and effective
skill/guidance compatibility portions with a cross-authority agent-environment
verdict. ADR 0029 retires the local-development authority. ADR 0030 supersedes
the remaining packaged-skill, provider-workflow, task-resource, and public
output portions. ADR 0031 removes Kast-owned skill and guidance installation
and moves Codex plugin authority to `amichne/kast-marketplace`; this record now
remains authoritative only for general manifest-backed trust and source
ownership not replaced there.

This ADR records the current contract for agent-facing Kast resources. It
exists so future agents can preserve the source of truth for repository-owned
packages, the internal command catalog, and generated validation artifacts
without maintaining compatibility branches for stale binaries.

## Context

Kast exposes several authored sources that can look like independent products:
the Copilot package source, command catalog, and generated protocol artifacts.
Older iterations used marker
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
| Protocol maintenance evidence | `cli-rs/protocol/maintenance/` | routing and output-format evaluation reports | `.github/scripts/test-kast-routing-evals.sh`, packaged content tests |
| Repo resource trust | `$HOME/.local/share/kast/install.json` | managed repo resource records with output checksums | `kast --output json ready`, verifier script |
| Developer-machine authority | `cli-rs/src/machine.rs` | one CLI, one IDEA plugin, and the selected remote Codex marketplace | `.github/scripts/test-local-development-refresh-contract.sh` and machine authority tests |

Marker files such as `.kast-version` and `.github/.kast-copilot-version` are
retired. They may be detected as stale state, but they are not trusted as a
current install signal.

## Hard requirements

Agent-facing changes must keep these requirements true:

- Repo-installed resources are recorded in `install.json` with kind, target,
  primitive version, source bundle checksum, output paths, and output checksums.
- `kast ready` and the source-tree verifier fail closed on missing, stale, or
  tampered manifest-backed resources.
- Kast does not generate repository guidance or install a standalone skill;
  the external Codex marketplace owns those surfaces.
- Stale active-binary/resource combinations report incompatibility and require
  upgrade or reinstall. Do not add a compatibility helper just for older
  binaries.
- A machine refresh activates one strict processless CLI and IDEA bundle, then
  fast-forwards the independently published Codex marketplace. It never
  creates a competing worktree-local runtime authority.

## Instruction topology

`AGENTS.md` files are authored repository instructions. Kast does not create or
patch them, and it does not generate `AGENTS.local.md`.

| Instruction file                               | Scope                                                                       |
|------------------------------------------------|-----------------------------------------------------------------------------|
| `AGENTS.md`                                    | Repo-wide build, type-safety, generated-output, and decision-record routing |
| `.agents/AGENTS.md`                            | Agent-only docs and local agent tooling                                     |
| `.agents/adr/AGENTS.md`                        | Agent-only decision records                                                 |
| `cli-rs/AGENTS.md`                             | Rust CLI, installer, manifest, agent command, and resource bundling work    |
| `cli-rs/resources/plugin/AGENTS.md`            | Authored Copilot package source                                             |
| `cli-rs/protocol/AGENTS.md`                    | Internal catalog, generated request fixtures, and maintenance evaluations   |
| `.github/AGENTS.md`                            | Authored GitHub automation vs generated Copilot package outputs             |
| `.agents/docs/AGENTS.md`                       | Published docs site source guidance kept out of the site                    |

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
