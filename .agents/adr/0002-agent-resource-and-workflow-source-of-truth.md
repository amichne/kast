# ADR 0002: Agent resource and workflow source of truth

Status: Accepted

Date: 2026-06-25

Supersession note: ADR 0005 supersedes the agent setup, harness selection,
Copilot package, `kast agent tools`, `kast agent call`, raw command, and
workflow CLI portions of this ADR. This ADR remains useful for historical
resource ownership context and manifest-backed trust rules not replaced by
ADR 0005.

This ADR records the current contract for agent-facing Kast resources. It
exists so future agents can preserve the source of truth for Copilot packages,
installable skills, installable instructions, and workflow commands without
maintaining compatibility branches for stale binaries.

## Context

Kast exposes several agent surfaces that can look like independent products:
the Copilot package under `.github`, installable Markdown instructions, the
packaged skill, the command catalog, and direct CLI workflows. Older iterations
used marker files and script-level workflow helpers to decide whether installed
copies were current. That made drift easy to miss and created pressure to keep
paths alive only because older installed binaries might not understand the new
shape.

The v1 contract needs one explicit rule: the active `kast` binary owns the
installed agent resources and workflow surface. If a repository has stale,
missing, or incompatible agent resources, the fix is to upgrade or reinstall
Kast and refresh from the active binary bundle. Do not add a maintained path
that exists only for older binaries.

## Decision

Kast will use manifest-backed resource records and first-class agent workflow
commands as the current source-of-truth model.

| Surface                   | Source of truth                                                      | Installed or generated output                                                                                                                                                    | Verification                                                                                                                                                                    |
|---------------------------|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Copilot package           | `cli-rs/resources/plugin/` and `primitive-manifest.json`             | `.github/lsp.json`, `.github/extensions/kast/**`                                                                                                                                 | `.github/scripts/test-kast-copilot-plugin.sh`, `.github/scripts/test-lsp-pivot-gates.sh`                                                                                        |
| RPC and tool catalog      | `cli-rs/resources/kast-skill/references/commands.json`               | internal request schemas, samples, LSP custom route metadata, `kast agent tools` specs                                                                                           | `cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- release generate contract --check`, `cargo test --manifest-path cli-rs/Cargo.toml --locked --test rpc_catalog_smoke` |
| Packaged skill            | `cli-rs/resources/kast-skill/SKILL.md`                               | thin installed `kast` skill entrypoint only                                                                                                                                      | `kast agent workflow package-verify`, CLI smoke tests                                                                                                                           |
| Installable instructions  | `cli-rs/resources/kast-instructions/`                                | thin installed instruction directories: `README.md`, `cli.md`, `tools.md`, `lsp.md`                                                                                              | `kast agent setup instructions --force`, docs content contract                                                                                                                  |
| Repo-local agent guidance | `cli-rs/src/install/agent_guidance.rs`                               | ignored root `AGENTS.local.md` plus explicit `--agents-md` targets, each with a managed `<kast files="*.kt, *.kts" type="instructions" replaceTools="grep,search,write">` region | CLI smoke tests, docs content contract                                                                                                                                          |
| Harness selection         | `projectOpen.agentHarness` and `kast agent setup auto --harness ...` | Copilot, skill, or instruction resource installs                                                                                                                                 | CLI smoke tests                                                                                                                                                                 |
| Repo resource trust       | `$HOME/.local/share/kast/install.json`                               | managed repo resource records with output checksums                                                                                                                              | `kast --output json ready`, verifier script                                                                                                                                     |
| Tool discovery            | `cli-rs/resources/kast-skill/references/commands.json`               | `kast agent tools` JSON specs for CLI-capable hosts and Copilot adapter loading                                                                                                  | CLI smoke tests, Copilot package tests                                                                                                                                          |
| Semantic workflows        | `kast agent workflow ...` in the active binary                       | workflow output directories with `input.json`, `stdout.json`, `stderr.txt`, and `workflow.json`                                                                                  | CLI smoke tests, workflow dry runs                                                                                                                                              |

Marker files such as `.kast-version` and `.github/.kast-copilot-version` are
retired. They may be detected as stale state, but they are not trusted as a
current install signal.

## Hard requirements

Agent-facing changes must keep these requirements true:

- The active `kast` binary provides `kast agent`, `kast agent tools`, and
  `kast agent workflow`.
- `kast agent setup auto` is harness-aware: explicit `--harness` wins,
  `projectOpen.agentHarness` wins over repository detection, and auto-detection
  is only the fallback. The portable skill and instruction harnesses must not
  require MCP availability.
- `kast agent setup auto --dry-run` reports the selected harness, selection
  source, reason, and equivalent direct install command without writing files.
  The reported command starts with the executable token used for the dry run,
  so `kast-dev`, copied test binaries, and absolute CLI paths remain valid.
- Copilot tools load specs from `kast agent tools` when the active binary is
  current, then call `kast agent call`; they do not synthesize a separate SDK
  tool contract or route through raw `kast rpc`.
- CLI-capable hosts can discover catalog-backed tools through
  `kast agent tools` without depending on a Copilot SDK or MCP adapter, then
  invoke the returned `result.invocation.argv` so alternate binary names and
  absolute binary paths keep working.
- Raw `kast rpc` remains a hidden debug escape hatch, not the public agent
  integration contract or installable instruction topic.
- Repo-installed resources are recorded in `install.json` with kind, target,
  primitive version, source bundle checksum, output paths, and output checksums.
- `kast agent setup` writes repo-local guidance to `AGENTS.local.md` by
  default, adds that file to `.git/info/exclude` unless auto-exclude is
  disabled, and owns only the managed `<kast ...>` region.
- IDEA and headless project-open setup run the same repo-local guidance
  command by default for Gradle workspaces; `projectOpen.profileAutoInit =
  false` is the explicit opt-out.
- `kast ready`, `kast agent workflow package-verify`, and the source-tree
  verifier fail closed on missing, stale, or tampered manifest-backed
  resources.
- `kast agent setup skill` installs only `SKILL.md`; source-only references,
  generated request samples, routing fixtures, and helper scripts stay under
  `cli-rs/resources/kast-skill/` for CLI, docs, tests, and validation.
- Mutating workflows require explicit mutation opt-in; dry runs only create
  evidence files.
- Stale active-binary/resource combinations report incompatibility and require
  upgrade or reinstall. Do not add a compatibility helper just for older
  binaries.

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
| `cli-rs/resources/kast-skill/AGENTS.md`        | Packaged skill, command catalog, request schemas, and workflow guidance     |
| `cli-rs/resources/kast-instructions/AGENTS.md` | Installable Markdown instruction source                                     |
| `.github/AGENTS.md`                            | Authored GitHub automation vs generated Copilot package outputs             |
| `.agents/docs/AGENTS.md`                       | Published docs site source guidance kept out of the site                    |
| `AGENTS.local.md` (special case)               | Ignored repo-local Kast setup guidance generated by the active binary       |

Add a scoped `AGENTS.md` only when a subtree has a real local delta: different
commands, source ownership, generated-output rules, or validation gates. Do not
create directory commentary that repeats the parent.

## Change process

When an agent-facing package or workflow changes:

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
