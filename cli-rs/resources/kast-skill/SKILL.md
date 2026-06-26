---
name: kast
description: >
  Use when an agent works in a Gradle project and needs the Rust `kast` CLI for
  file discovery, Kotlin source reads or edits, symbol identity, references,
  callers, hierarchy, diagnostics, source-index metrics, install/config/package
  verification, file-backed Kast calls, or focused Gradle validation. Prefer
  Kast before generic text or file tools for Kotlin and broad Gradle project
  exploration.
---

# Kast

Kast is the installed Rust `kast` CLI semantic surface for Kotlin and Gradle
repositories. When this skill is present, assume the binary installed it and use
`kast` directly for file discovery, Kotlin context, symbol identity,
relationships, diagnostics, edits, and focused validation. Default to Kast for
Gradle project file operations that need semantic or install-state evidence.

## First Move

Confirm the command surface, then use Kast before ordinary text tools for
Kotlin or Gradle project facts:

```console
command -v kast
kast --help
kast agent --help
kast agent tools
kast agent workflow --help
```

When the installed skill exposes `scripts/`, prefer the read-only verifier for
install, package, config, active-binary, and project-readiness evidence:

```console
python3 scripts/verify-kast-state.py --workspace-root "$PWD" --require-gradle-project
```

If the binary is missing or does not expose the expected agent tool/workflow
surface, report that the installed skill and active binary are incompatible and
require a CLI upgrade/reinstall. Do not replace the missing compiler-backed path
with non-semantic Kotlin search.

If a command reports `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`,
`METRICS_DB_UNAVAILABLE`, or a missing source-index database, warm the
repository with `kast agent up` when setup state may also be stale, or warm the
IDE-hosted backend directly when only runtime state is missing:

```console
kast agent up --workspace-root "$PWD" --dry-run
kast --output json agent up --workspace-root "$PWD" --no-onboard
kast runtime up --workspace-root "$PWD" --backend idea
```

Interactive human `kast agent up` may offer first-run IDEA/Copilot onboarding
with global or repository-scoped defaults. Agents should use JSON output or
`--no-onboard` so prompts cannot block execution. Runtime warmup may open IDEA
or Android Studio only when
`runtime.ideaLaunch.enabled` is set in Kast config; otherwise it reports that
the project must be opened in the IDE. Treat the failure as a blocker only
after this dynamic IDE warmup path has failed.

## Gradle File Routing

- Use for Gradle project file work, not only direct Kotlin edits.
- Unknown symbol: start with `kast agent call symbol/query`; use tight `query`, `limit`,
  `modes`, and filters such as `relativePathPrefix`, `gradleProject`,
  `sourceSet`, or `kinds`.
- Ambiguous symbol: escalate through `raw/workspace-symbol`, `symbol/discover`,
  then `symbol/resolve`; inspect with `symbol/scaffold`, `raw/file-outline`,
  `symbol/references`, or `symbol/callers`.
- Unknown file/module: use `raw/workspace-files includeFiles=false`; request
  paths only with `moduleName` and a small `maxFilesPerModule`.
- Unknown Kotlin text, comments, or literals: use `raw/workspace-search`.
- Known non-Kotlin path (`build.gradle.kts`, docs, YAML, JSON, shell): normal
  file tools are fine. Use Kast to discover the owning module or likely path
  when the path is not already exact.
- Kotlin edits: `symbol/rename`, `symbol/write-and-validate`,
  `raw/semantic-insertion-point`, `raw/completions`, `raw/code-actions`,
  `raw/apply-edits`, `raw/optimize-imports`; still run Kast before external
  patches and diagnostics after.
- Impact/proof: `raw/type-hierarchy`, `raw/implementations`,
  `database/metrics`, `kast inspect metrics fan-in`, other `kast inspect metrics` subcommands,
  `kast inspect demo --json`, `raw/workspace-refresh`, `raw/diagnostics`, then the
  narrowest Gradle task.

## Request Discipline

For repeated semantic sequences, use `kast agent workflow ...`; the active CLI
must provide this command. For one nontrivial catalog call, use
`scripts/kast-agent-call.py` so params, stdout, and stderr are preserved as
files. Send catalog methods through `kast agent call <method> --params-file
"$KAST_PARAMS"` with `--workspace-root "$PWD"` when scripting manually. Use
camelCase fields, absolute paths, and check the agent envelope `ok` plus the
nested result status; validation errors, `ok=false`, dirty diagnostics, hash
mismatches, and failed Gradle tasks fail the operation.
Load `references/commands.yaml`, `references/commands.json`, or
`references/requests/` only for exact fields, variants, enum values, or samples.
Use `references/runbook.md` only when debugging raw transport or preserving a
full JSON-RPC envelope matters.

Read `references/workflows.md` for install/refresh/verify ownership, project
readiness gates, file-backed request exchange, semantic request sequences, and
failure recovery.

## Boundaries

Do not use `grep`, `rg`, `ast-grep`, manual parsing, or ordinary file reads for
Kotlin symbol identity, usage sets, hierarchy, insertion points, or rename
scope. Use them for exact non-Kotlin paths, generated text, docs, skill
maintenance, and final absence verification after Kast finds no candidates.

When a Kotlin request names only a symbol, resolve it with Kast before reading
or editing. For exact Kotlin-file textual cleanup, read only what is needed and
run Kast diagnostics before claiming completion.
