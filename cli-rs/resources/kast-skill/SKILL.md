---
name: kast
description: >
  Use when an agent works in a Gradle project and needs the Rust `kast` CLI for
  file discovery, Kotlin source reads or edits, symbol identity, references,
  callers, hierarchy, diagnostics, source-index metrics, or focused Gradle
  validation. Prefer Kast before generic text or file tools for Kotlin and broad
  Gradle project exploration.
metadata:
  short-description: Semantic Gradle project file operations
---

# Kast

Kast is the installed Rust `kast` CLI semantic surface for Kotlin and Gradle
repositories. When this skill is present, assume the binary installed it and use
`kast` directly for file discovery, Kotlin context, symbol identity,
relationships, diagnostics, edits, and focused validation.

## First Move

Confirm the command surface, then use Kast before ordinary text tools for
Kotlin or Gradle project facts:

```console
command -v kast
kast --help
kast agent --help
```

If the binary is missing or does not expose the expected agent surface, report
that the installed skill and binary are out of sync. Do not replace the missing
compiler-backed path with non-semantic Kotlin search.

If a command reports `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`,
`METRICS_DB_UNAVAILABLE`, or a missing source-index database, warm the
IDE-hosted backend before falling back:

```console
kast up --workspace-root "$PWD" --backend idea
```

This may open IDEA or Android Studio only when `runtime.ideaLaunch.enabled` is
set in Kast config; otherwise it reports that the project must be opened in the
IDE. Treat the failure as a blocker only after this dynamic IDE warmup path has
failed.

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
  `database/metrics`, `kast metrics fan-in`, other `kast metrics` subcommands,
  `kast demo --json`, `raw/workspace-refresh`, `raw/diagnostics`, then the
  narrowest Gradle task.

## Request Discipline

For nontrivial CLI automation, write params, result, and stderr to temp files;
see `references/quickstart.md` for the harness. Send catalog methods through
`kast agent call <method> --params-file "$KAST_PARAMS" --workspace-root "$PWD"`.
Use camelCase fields, absolute paths, and check the agent envelope `ok` plus
the nested result status; validation errors, `ok=false`, dirty diagnostics,
hash mismatches, and failed Gradle tasks fail the operation.
Load `references/commands.yaml`, `references/commands.json`, or
`references/requests/` only for exact fields, variants, enum values, or samples.
Use `references/runbook.md` only when debugging raw transport or preserving a
full JSON-RPC envelope matters.

## Boundaries

Do not use `grep`, `rg`, `ast-grep`, manual parsing, or ordinary file reads for
Kotlin symbol identity, usage sets, hierarchy, insertion points, or rename
scope. Use them for exact non-Kotlin paths, generated text, docs, skill
maintenance, and final absence verification after Kast finds no candidates.

When a Kotlin request names only a symbol, resolve it with Kast before reading
or editing. For exact Kotlin-file textual cleanup, read only what is needed and
run Kast diagnostics before claiming completion.
