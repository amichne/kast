# Kast agent guide

ALways TDD with tracer bullets, use the skill

Kast is a Kotlin analysis tool with one line-delimited JSON-RPC contract and two supported operator paths: the
repo-local `kast` CLI manages a standalone JVM daemon for local automation and CI, and the IntelliJ plugin backend runs
inside a running IntelliJ IDEA instance.

Subdirectory `AGENTS.md` files narrow these rules for their own units. When a rule exists in both places, follow the
deeper file.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `amichne/kast` using the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the canonical label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`,
`wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo: use root `CONTEXT.md` and `docs/adr/` when they exist. See `docs/agents/domain.md`.

## North stars

Carry these principles into every change in this repository.

We admire innovation and admonish adherents. We view simplicity as the truest form of excellence. We know without the
ability to communicate our ideas we're a boat adrift, hopeless and helpless. These are your north stars, no matter the
context.

Do not express positive or negative opinions unless they pass this gate: the object of evaluation is clear, the criteria
are appropriate, the evidence is sufficient, a baseline has been considered, and confidence is calibrated. If those
conditions are not met, narrow the claim or state that a firm judgment is not justified.

## Unit map

Use this map to choose the narrowest unit that owns a change.

- `analysis-api`: shared contract, serializable models, JSON-RPC wire types, descriptor discovery helpers, standalone
  option parsing, errors, file edit validation, descriptor schema, and disk edit helpers
- `kast-cli`: CLI control plane, command parsing, JSON output, install flows, runtime orchestration, wrapper packaging,
  portable distribution layout, and the native-image entrypoint
- `analysis-server`: JSON-RPC dispatch, local socket and stdio transport, request limits, and descriptor lifecycle
- `index-store`: SQLite source index persistence, file manifest state, workspace discovery cache payload storage, and
  generic reference-index batching without IntelliJ or backend runtime dependencies
- `backend-standalone`: standalone host, Analysis API session bootstrap, Gradle workspace discovery, PSI/K2-backed
  analysis helpers, and runtime startup
- `backend-intellij`: IntelliJ IDEA plugin backend, project-level service, plugin lifecycle, and IDE-hosted analysis
  server
- `backend-shared`: shared analysis utilities consumed by both backend runtimes via compileOnly IntelliJ platform
  dependencies
- `shared-testing`: fake backend fixtures and shared contract assertions for tests
- `build-logic`: Gradle convention plugins, runtime-lib sync, wrapper generation, and shared build configuration
- `docs`: Zensical source docs, published usage guidance, and implementation notes
- `site`: generated static site output for GitHub Pages

## Mandatory tool routing

Agents must use the native `kast_*` Copilot tools registered by the
`.github/extensions/kast/` extension for Kotlin semantic operations. The extension also resolves the repo-local `kast`
CLI at session start, so the same machine contract is available as a `kast rpc '<jsonrpc-request>'`
bash fallback.

| Operation             | Native tool                      | Bash fallback                                            |
|-----------------------|----------------------------------|----------------------------------------------------------|
| Any analysis/mutation | `kast_<tool>` (native extension) | `kast rpc '{"method":"<method>","params":{...},"id":1}'` |

The native `kast_*` tools registered by `.github/extensions/kast/extension.mjs`
remain the preferred interface. The `kast rpc` CLI command is the universal fallback — it accepts any JSON-RPC method
the daemon supports and auto-ensures the daemon.

Native tool names for discoverability: `kast_workspace_files`,
`kast_workspace_symbol`, `kast_workspace_search`, `kast_file_outline`,
`kast_scaffold`, `kast_resolve`, `kast_references`, `kast_callers`,
`kast_metrics`, `kast_diagnostics`, `kast_rename`, and
`kast_write_and_validate`. These tools remain preferred; use the matching JSON-RPC method via `kast rpc` when a CLI
fallback is needed.

**Prohibited substitutions:** `grep`, `rg`, `ast-grep`, `cat` + manual parsing must NOT be used for symbol identity,
reference finding, or call hierarchy. These tools lack semantic understanding and produce incorrect results for
overloaded symbols, inherited members, and cross-module references.

**Text search whitelist:** `grep`/`rg` may be used for finding file paths and searching non-Kotlin files. For Kotlin
source, use
`kast_workspace_symbol` for symbol-name searches and
`kast_workspace_search` for string, comment, and arbitrary content searches.

## Agent hooks

`.github/hooks/hooks.json` is the authoritative source for GitHub Copilot hook configuration in this repository. Use the
standard Copilot hook schema:
`{"version":1,"hooks":{...}}` with command hooks only. The repo-level hooks use `sessionStart` plus `postToolUse` state
capture to track session-owned file edits, then run final command-based validation from `sessionEnd`. Workflow guidance
that depends on skills, such as `refresh-affected-agents` or docs refresh, belongs in agent instructions rather than in
the hook manifest.

## Copilot extension

`.github/extensions/kast/extension.mjs` is the primary entry point for Copilot-assisted Kotlin work. It

- resolves the `kast` CLI path once at session start (no bootstrap turn),
- registers the `kast_*` tools listed under **Mandatory tool routing**, and
- soft-warns once per session when generic `view`/`grep`/`rg`/`edit`/`create`
  targets a `.kt`/`.kts` path, suggesting the semantic equivalent.

When the extension loads successfully, it shadows `.agents/skills/kast/SKILL.md`
for routine routing. Fall back to the skill doc only when the extension is unavailable or when you need deeper
command-shape or recovery guidance, and never use `grep`/`rg`/`ast-grep` for symbol operations.

## Skill composition

| Phase               | Primary skill               | Supporting skill     |
|---------------------|-----------------------------|----------------------|
| Understand the code | `kast` (scaffold, explore)  | —                    |
| Plan a change       | `kast` (impact, scaffold)   | —                    |
| Make the change     | `kast` (write-and-validate) | `kotlin-standards`   |
| Validate the change | `kast` (diagnostics)        | `kotlin-gradle-loop` |
| Document the change | `docs-writer`               | —                    |

## Working rules

Apply these rules across the repo before local unit rules add more detail.

- Change the smallest unit that owns the behavior. Pull shared semantics down into `analysis-api` only when multiple
  hosts or transports need them.
- Keep host-specific dependencies out of shared units. `analysis-api` and
  `analysis-server` must stay free of IntelliJ-only APIs.
- Keep standalone PSI and K2 Analysis API helpers in `backend-standalone`
  unless another surviving runtime genuinely needs them.
- Use `kast` in commands, docs, and packaging targets. `analysis-cli` is a historical path and should not receive new
  references.
- Treat API model changes as contract changes. Preserve schema compatibility, absolute-path invariants, descriptor
  fields, and capability advertising unless the behavior is intentionally changing across the stack.
- Keep capability gating honest. A transport or backend must not advertise support for work it cannot actually perform.
- Respect the current architecture: `kast-cli` owns the operator-facing control plane, native entrypoint, wrapper
  packaging, and portable distribution, `analysis-server` owns transport and descriptor plumbing,
  `backend-standalone` owns headless runtime behavior, `backend-intellij` owns IDE-hosted runtime behavior, and
  `shared-testing` stays out of production code paths.
- Treat `docs/` plus `zensical.toml` as the documentation source of truth.
  `site/` is generated output and should be rebuilt, not hand-edited.
- Prefer repo-root packaging entry points for shipped artifacts: `./kast.sh build`
  builds the portable distribution artifacts; `./gradlew buildIntellijPlugin` builds the IntelliJ plugin zip.
- Verify with the narrowest Gradle task that proves the change. Broaden the scope when you touch shared contracts, build
  logic, or cross-module behavior.

## Contract surface inventory

Before modifying `EmbeddedSkillResources`, `EmbeddedCopilotExtensionResources`,
`AnalysisBackend`, the `kast rpc` machine contract surface, or any packaged artifact manifest, enumerate all consumers:
`docs/openapi.yaml`, `.agents/skills/kast/SKILL.md`,
`.agents/skills/kast/evals/**/*`,
`.agents/skills/kast/references/*`, `.agents/skills/kast/scripts/*`,
`evaluation/**/*`, `.github/extensions/kast/extension.mjs`,
`.github/agents/**/*`, `.github/hooks/**/*`, `kast-cli/build.gradle.kts`, and `kast.sh`. These are contract surfaces — a
change without updating all consumers silently breaks the distribution.
