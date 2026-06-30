---
name: kast
description: >
  Use when an agent works in a Gradle project and needs the Rust `kast` CLI for
  file discovery, all Kotlin `.kt` and `.kts` source reads or edits, symbol
  identity, references, callers, hierarchy, diagnostics, source-index metrics,
  install/config/package verification, file-backed Kast calls, or focused
  Gradle validation. Prefer Kast before generic text or file tools for Kotlin
  and broad Gradle project exploration.
---

# Kast

Kast is the installed Rust `kast` CLI semantic surface for Kotlin and Gradle
repositories. When this skill is present, assume the binary installed it and use
`kast` directly for file discovery, Kotlin context, symbol identity,
relationships, diagnostics, edits, and focused validation. Default to Kast for
every `.kt` and `.kts` file and for Gradle project file operations that need
semantic or install-state evidence. Treat Kast as the only navigation surface
for Kotlin semantics until it returns a concrete blocker or a bounded
non-semantic task remains.

## Continuity Rule

Keep using Kast after the first successful call for the same Kotlin or Gradle
task. A first Kast result is not a handoff back to generic file reads; continue
with `symbol/scaffold`, `symbol/references`, `symbol/callers`,
`raw/diagnostics`, `raw/workspace-refresh`, or the matching
`kast agent workflow ...` command until the task leaves Kotlin semantics or
Kast reports a concrete blocker.

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

For install, package, config, active-binary, and project-readiness evidence,
use the native package verification workflow:

```console
kast --output json agent workflow package-verify --workspace-root "$PWD" --require-gradle-project
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
- Any `.kt` or `.kts` file: start with `symbol/scaffold` when you need content
  plus declaration context, or `raw/file-outline` when only structure is needed.
  Do not open Kotlin files with generic readers before Kast has provided the
  bounded target and semantic context.
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

## Public Surface

Use the public agent surfaces first: `kast agent`, `kast agent tools`,
`kast agent workflow`, `kast inspect metrics`, and the catalog method names
disclosed by `kast agent tools`. Named tools such as `kast_symbol_query`,
`kast_resolve`, `kast_references`, `kast_callers`, and `kast_metrics` are the
host-facing way to expose navigation, relationships, source-index database
access, and edits. Do not teach generated protocol paths, LSP capability
internals, daemon routing details, raw transport, or implementation class names
as the public API for agents.

## Request Discipline

For repeated semantic sequences, use `kast agent workflow ...`; the active CLI
must provide this command. For one nontrivial catalog call, send the method
through `kast agent call <method> --params-file "$KAST_PARAMS"` with
`--workspace-root "$PWD"` and keep the params, stdout, and stderr as files when
evidence needs to be preserved. Use camelCase fields, absolute paths, and check
the agent envelope `ok` plus the nested result status; validation errors,
`ok=false`, dirty diagnostics, hash mismatches, and failed Gradle tasks fail the
operation.

Normal installed use loads only `SKILL.md`. Do not pre-load the full catalog,
generated request samples, raw transport notes, or source-only references before
a concrete call needs them. Discover available methods, request schemas,
default arguments, mutation metadata, and invocation argv through
`kast agent tools`; use `kast agent workflow --help` for supported multi-step
operations. The Kast source tree keeps generated catalogs and request fixtures
for CLI validation, but they are not part of the installed skill payload.

## Boundaries

Do not use `grep`, `rg`, `ast-grep`, manual parsing, or ordinary file reads for
Kotlin symbol identity, usage sets, hierarchy, insertion points, or rename
scope. Use them for exact non-Kotlin paths, generated text, docs, skill
maintenance, and final absence verification after Kast finds no candidates.

When a Kotlin request names only a symbol, resolve it with Kast before reading
or editing. For exact Kotlin-file textual cleanup, read only what is needed and
run Kast diagnostics before claiming completion.
