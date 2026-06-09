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

Kast is the Rust `kast` CLI semantic surface for Kotlin and Gradle
repositories. Default to Kast for Gradle project file operations that need file
discovery, Kotlin context, symbol identity, relationships, diagnostics, edits,
or focused Gradle validation.

## First Move

Run `command -v kast` and `kast --help` before project exploration. If `kast`
is missing, run `eval "$(bash "$SKILL_DIR/scripts/kast-session-start.sh")"`
with `SKILL_DIR` set to this skill directory, then retry. Stop if setup still
fails or reports a skill/CLI version mismatch.

## Gradle File Routing

- Use for Gradle project file work, not only direct Kotlin edits.
- Unknown symbol: start with `symbol/query`; use tight `query`, `limit`,
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

For nontrivial RPC calls, write request, result, and stderr to temp files; see
`references/quickstart.md` for the harness. Validate payloads with
`scripts/validate-rpc-request.py`, then run `kast rpc --request-file
"$KAST_REQUEST" --workspace-root "$PWD"`. Use camelCase fields, absolute paths,
and check `ok` plus `type`; validation errors, `ok=false`, dirty diagnostics,
hash mismatches, and failed Gradle tasks fail the operation.
Load `references/commands.yaml`, `references/commands.json`, or
`references/requests/` only for exact fields, variants, enum values, or samples.

## Boundaries

Do not use `grep`, `rg`, `ast-grep`, manual parsing, or ordinary file reads for
Kotlin symbol identity, usage sets, hierarchy, insertion points, or rename
scope. Use them for exact non-Kotlin paths, generated text, docs, skill
maintenance, and final absence verification after Kast finds no candidates.

When a Kotlin request names only a symbol, resolve it with Kast before reading
or editing. For exact Kotlin-file textual cleanup, read only what is needed and
run Kast diagnostics before claiming completion.
