# Kast agent guide

All developement should be done with TDD and the narrowest possible scope.
Use the unit map below to choose the smallest unit that owns the behavior you're working on,
and write tests that prove the behavior before implementing it.

All dependencies must be declared in libs.version.toml (violating this rule is a common source of build breakage and test flakiness).
If you need a new dependency, add it to the narrowest unit that needs it and update all consumers.

Kast is a Kotlin analysis tool with one line-delimited JSON-RPC contract and
two supported operator paths: the repo-local `kast` CLI manages a headless JVM
daemon for local automation and CI, and the IDEA plugin backend runs inside
a running IDEA or Android Studio instance.

Subdirectory `AGENTS.md` files narrow these rules for their own units. When a
rule exists in both places, follow the deeper file.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `amichne/kast` using the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the canonical label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo: use root `CONTEXT.md` and `docs/adr/` when they exist. See `docs/agents/domain.md`.

## Workspace repo coordination

`workspace.repos.toml` is the source of truth for generated or mirrored
repositories that move with this repo but are not part of this Git history.
Treat those entries as sibling checkouts, not vendored source trees.

- The Rust CLI now lives in `cli-rs/` inside this repository.
- The expected local release mirror layout is `kast/` and `homebrew-kast/` as
  siblings under the same parent directory.
- Run `scripts/workspace-sync-status.sh` before cross-repo release,
  migration, or CLI handoff work. Use `--strict` when automation should fail
  on a missing checkout, remote mismatch, or branch mismatch.

## North stars

Carry these principles into every change in this repository.

We admire innovation and admonish adherents. We view simplicity as the truest
form of excellence. We know without the ability to communicate our ideas we're
a boat adrift, hopeless and helpless. These are your north stars, no matter the
context.

Do not express positive or negative opinions unless they pass this gate: the
object of evaluation is clear, the criteria are appropriate, the evidence is
sufficient, a baseline has been considered, and confidence is calibrated. If
those conditions are not met, narrow the claim or state that a firm judgment is
not justified.

## Unit map

Use this map to choose the narrowest unit that owns a change.

- `analysis-api`: shared contract, serializable models, JSON-RPC wire types,
  descriptor discovery helpers, server launch options, errors, file edit
  validation, descriptor schema, and disk edit helpers
- `analysis-server`: JSON-RPC dispatch, local socket and stdio transport,
  request limits, and descriptor lifecycle
- `index-store`: SQLite source index persistence, file manifest state,
  workspace discovery cache payload storage, and generic reference-index
  batching without IDEA or backend runtime dependencies
- `backend-headless`: headless host, Analysis API session bootstrap,
  packaged IDEA runtime bootstrap, and runtime startup
- `backend-idea`: IDEA / Android Studio plugin backend, project-level service,
  plugin lifecycle, and IDE-hosted analysis server
- `backend-shared`: shared analysis utilities consumed by both backend
  runtimes via compileOnly IDEA platform dependencies
- `analysis-api/src/testFixtures`: fake backend fixtures and shared contract
  assertions for tests
- `build-logic`: Gradle convention plugins, runtime-lib sync, wrapper
  generation, and shared build configuration
- `docs`: Zensical source docs, published usage guidance, and implementation
  notes
- `site`: generated static site output for GitHub Pages

## Mandatory tool routing

Agents must use the native `kast_*` Copilot tools registered by the
`.github/extensions/kast/` extension for Kotlin semantic operations.
The extension also resolves the repo-local `kast` CLI at session start, so
the same machine contract is available as a `kast rpc '<jsonrpc-request>'`
bash fallback.

| Operation             | Native tool                      | Bash fallback                                            |
|-----------------------|----------------------------------|----------------------------------------------------------|
| Any analysis/mutation | `kast_<tool>` (native extension) | `kast rpc '{"method":"<method>","params":{...},"id":1}'` |

The native `kast_*` tools registered by `.github/extensions/kast/extension.mjs`
remain the preferred interface. The `kast rpc` CLI command is the universal
fallback — it accepts any JSON-RPC method the daemon supports and auto-ensures
the daemon.

The v1 RPC surface is split into three explicit method families plus system
methods:
- `symbol/*`: name-based orchestration such as `symbol/resolve`,
  `symbol/references`, `symbol/callers`, `symbol/scaffold`,
  `symbol/rename`, and `symbol/write-and-validate`
- `raw/*`: direct offset/file-based backend operations such as `raw/resolve`,
  `raw/diagnostics`, `raw/workspace-files`, and `raw/workspace-search`
- `database/*`: Rust-owned source-index queries such as `database/metrics`;
  these are handled by the CLI before JVM daemon passthrough
- system methods: `health`, `runtime/status`, and `capabilities`

Native tool names for discoverability: `kast_workspace_files`,
`kast_workspace_symbol`, `kast_workspace_search`, `kast_file_outline`,
`kast_scaffold`, `kast_resolve`, `kast_references`, `kast_callers`,
`kast_metrics`, `kast_diagnostics`, `kast_rename`, and
`kast_write_and_validate`. These tools remain preferred; use the matching
JSON-RPC method via `kast rpc` when a CLI fallback is needed.

Do not add JVM handlers for operational SQLite reads. Kotlin may hydrate and
write the source index for headless or IDE-backed indexing, but source-index
query methods such as `database/metrics` and SQLite-backed `symbol/query` are
owned by the Rust CLI in `cli-rs/`.

**Prohibited substitutions:** `grep`, `rg`, `ast-grep`, `cat` + manual
parsing must NOT be used for symbol identity, reference finding, or call
hierarchy. These tools lack semantic understanding and produce incorrect
results for overloaded symbols, inherited members, and cross-module
references.

**Text search whitelist:** `grep`/`rg` may be used for finding file paths and
searching non-Kotlin files. For Kotlin source, use
`kast_workspace_symbol` for symbol-name searches and
`kast_workspace_search` for string, comment, and arbitrary content searches.

## Copilot extension resources

The packaged Copilot integration is extension-only in this repository. Do not
add `.github/hooks` for Kast install behavior. Managed Copilot files live under
`.github/extensions/kast`, and `kast install copilot` writes the extension-local
version marker at `.github/extensions/kast/.kast-copilot-version`. Workflow
guidance belongs in agent instructions, `.github/copilot-instructions.md`,
`.github/instructions`, or packaged extension agent material rather than in a
hook manifest.

## Copilot extension

`.github/extensions/kast/extension.mjs` is the primary entry point for
Copilot-assisted Kotlin work. It

- resolves the `kast` CLI path once at session start (no bootstrap turn),
- registers the `kast_*` tools listed under **Mandatory tool routing**, and
- soft-warns once per session when generic `view`/`grep`/`rg`/`edit`/`create`
  targets a `.kt`/`.kts` path, suggesting the semantic equivalent.

When the extension loads successfully, it shadows `cli-rs/resources/kast-skill/SKILL.md`
for routine routing. Fall back to the skill doc only when the extension is
unavailable or when you need deeper command-shape or recovery guidance, and
never use `grep`/`rg`/`ast-grep` for symbol operations.

## Skill composition

| Phase               | Primary route               | Supporting route                  |
|---------------------|-----------------------------|-----------------------------------|
| Understand the code | `kast` (scaffold, explore)  | —                                 |
| Plan a change       | `kast` (impact, scaffold)   | —                                 |
| Make the change     | `kast` (write-and-validate) | `kotlin-standards`                |
| Validate the change | `kast` (diagnostics)        | Kast extension Gradle-loop tools  |
| Document the change | `docs-writer`               | —                                 |

## Working rules

Apply these rules across the repo before local unit rules add more detail.

- Change the smallest unit that owns the behavior. Pull shared semantics down
  into `analysis-api` only when multiple hosts or transports need them.
- Keep host-specific dependencies out of shared units. `analysis-api` and
  `analysis-server` must stay free of IDEA-only APIs.
- Keep headless runtime behavior in `backend-headless` unless another
  surviving runtime genuinely needs it.
- Use `kast` in commands, docs, and packaging targets.
- Treat API model changes as contract changes. Preserve schema compatibility,
  absolute-path invariants, descriptor fields, and capability advertising
  unless the behavior is intentionally changing across the stack.
- Keep capability gating honest. A transport or backend must not advertise
  support for work it cannot actually perform.
- Respect the current architecture: the Rust CLI in `cli-rs/` owns the
  operator-facing control plane, installer, packaged skill, and Copilot
  extension distribution; `analysis-server` owns transport and
  descriptor plumbing, `backend-headless` owns headless runtime behavior,
  `backend-idea` owns IDE-hosted runtime behavior, and
  `analysis-api` test fixtures stay out of production code paths.
- Treat `docs/` plus `zensical.toml` as the documentation source of truth.
  `site/` is generated output and should be rebuilt, not hand-edited.
- Prefer repo-root packaging entry points for shipped artifacts: `./kast.sh build`
  builds the portable distribution artifacts; `./gradlew buildIdeaPlugin` builds the IDEA
  plugin zip.
- Verify with the narrowest Gradle task that proves the change. Broaden the
  scope when you touch shared contracts, build logic, or cross-module behavior.

## Contract surface inventory

Before modifying `AnalysisBackend`, the `kast rpc` machine contract surface, or
any packaged artifact manifest,
enumerate all consumers: `docs/openapi.yaml`, `cli-rs/resources/kast-skill/SKILL.md`,
`cli-rs/resources/kast-skill/evals/**/*`,
`cli-rs/resources/kast-skill/references/*`, `cli-rs/resources/kast-skill/scripts/*`,
`evaluation/**/*`, `.github/extensions/kast/extension.mjs`,
`.github/extensions/kast/**/*`, `.github/instructions/**/*`,
`.github/copilot-instructions.md`, `cli-rs/resources/**/*`, and
`kast.sh`.
These are contract surfaces — a change without updating all consumers silently
breaks the distribution.
