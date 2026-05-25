---
name: kast
description: >
  Semantic Kotlin navigation, indexed source exploration, diagnostics, Gradle
  workflow routing, and validated refactoring through the Rust `kast` CLI. Use
  this whenever a task involves Kotlin symbol identity, fuzzy symbol/file
  discovery, project reading, references, callers, diagnostics, source-index
  metrics, database-backed impact analysis, safe edits, renames, or Kotlin
  Gradle validation. Never use grep, rg, sed, or manual parsing for Kotlin
  identity.
metadata:
  short-description: Semantic Kotlin navigation and validated edits
---

# Kast

Use Kast whenever Kotlin work depends on declaration identity, fuzzy symbol
discovery, source-index metrics, call flow, diagnostics, or validated edits. The
authoritative executable is the Rust `kast` CLI.

## Start Here

Run the narrowest semantic query first through `kast`. The ideal end state is
simple: `command -v kast` succeeds, and every workflow calls `kast` directly
from `PATH`. Use direct subcommands for lifecycle, metrics, and demo work; use
`kast rpc` for semantic Kotlin analysis and mutations.

If `kast` is missing, treat that as setup drift. Use the bootstrap helper once
to recover the current session:

```bash
eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
```

Then retry the same `kast ...` command. If bootstrap cannot resolve `kast`,
report that setup blocker instead of falling back to non-semantic Kotlin search.
Do not introduce workflow examples that require CLI-path environment variables,
repo-local binary paths, or host-specific tool names.

When calling `kast` from a shell, capture JSON to files and read the file:

```bash
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"
kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-files","params":{"includeFiles":true},"id":1}' \
  --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

Never parse large Kast JSON from terminal output. Inspect `KAST_STDERR` only
when the command fails.

## Command Router

Use this table for fuzzy routing. Load `references/commands.json` only when you
need exact JSON-RPC field names, response types, or discriminated variants.

| Need | Preferred route |
| --- | --- |
| Warm or inspect a workspace backend | `kast up`, `kast status`, `kast capabilities` |
| Discover modules and Kotlin files | `kast rpc` method `raw/workspace-files` |
| Fuzzy symbol discovery by name | `kast rpc` method `raw/workspace-symbol` |
| Guided symbol disambiguation | `kast rpc` method `symbol/discover` |
| Fuzzy text search in Kotlin source | `kast rpc` method `raw/workspace-search` |
| Read a Kotlin file semantically | `kast rpc` method `symbol/scaffold` |
| Read a lightweight declaration tree | `kast rpc` method `raw/file-outline` |
| Resolve exact declaration identity | `kast rpc` method `symbol/resolve` |
| Find usages | `kast rpc` method `symbol/references` |
| Trace callers or callees | `kast rpc` method `symbol/callers` |
| Offset-based resolve/references/hierarchy | `raw/resolve`, `raw/references`, `raw/call-hierarchy`, `raw/type-hierarchy` |
| Find implementations/subclasses | `raw/implementations` |
| Find insertion points, completions, actions | `raw/semantic-insertion-point`, `raw/completions`, `raw/code-actions` |
| Run diagnostics | `kast rpc` method `raw/diagnostics` |
| Rename safely | `kast rpc` method `symbol/rename` |
| Apply validated Kotlin edits or create files | `kast rpc` method `symbol/write-and-validate` |
| Apply raw edit plans or optimize imports | `raw/apply-edits`, `raw/optimize-imports` |
| Refresh after external edits | `raw/workspace-refresh` |
| Query source-index metrics through Rust RPC | `kast rpc` method `database/metrics` |
| Query SQLite source-index directly | `kast metrics fan-in`, `fan-out`, `dead-code`, `impact`, `coupling`, `search`, `graph --json` |
| Explore indexed symbol graph interactively or as JSON | `kast demo`, usually with `--json` for agents |
| Run build/test validation | Use the repo Gradle command after Kast narrows the affected files or symbols |

## Workflow Patterns

For exploration, start broad but semantic: `workspace-files` for module shape,
`workspace-symbol` for broad name search, `symbol/discover` when you have a
simple name plus file/line/snippet context, `workspace-search` for
literals/comments, then `scaffold` or `file-outline` on the best file. Avoid
opening many files.

For identity work, use `symbol/discover` first when a name is ambiguous or you
have local code context. Then call `symbol/resolve` with the returned
`resolveParams`. Use simple symbol names with `kind`, `containingType`, and
`fileHint` to disambiguate; do not pass fully-qualified names to
`symbol/resolve` unless the command spec explicitly says a field wants one.
Request `includeDeclarationScope`, `includeDocumentation`, `surroundingLines`,
or `includeSurroundingMembers` only when the extra context is needed.

For impact work, combine semantic and index views. Use `references` and
`callers` for compiler-backed truth, then `kast metrics impact <fqName> --json`,
`fan-in`, `fan-out`, or `coupling` for broader ranking and prioritization.
Treat direct SQLite metrics as index-backed guidance; refresh or use RPC when
the index may be stale.

For edits, prefer `kast rpc` method `symbol/write-and-validate` for `.kt` and
`.kts` changes. Use `symbol/rename` for renames, `symbol/write-and-validate`
for create/insert/replace, then run `raw/diagnostics` or the repo Gradle task
that proves the change. If diagnostics are stale after external edits, run
`raw/workspace-refresh` before judging the result.

## Rules

- All JSON-RPC request and response fields use camelCase.
- Path fields such as `workspaceRoot`, `filePath`, `filePaths`, `targetFile`,
  and `contentFile` must be absolute unless the command help says otherwise.
- Check `ok` and `type` before projecting results. Treat `ok=false`,
  `*_FAILURE`, dirty diagnostics, validation failures, hash mismatches, or
  failed Gradle tasks as failed operations.
- `symbol/rename` and `symbol/write-and-validate` require a `type`
  discriminator. Use `references/commands.json` for exact variant names.
- If a projection fails, inspect one sample object from `KAST_RESULT` and fix
  the projection.
- If output is too large, narrow the semantic query before post-processing.
- Use `grep` or `rg` only for non-Kotlin files, path discovery, comments,
  string literals, generated text, or skill maintenance. Never use them for
  Kotlin symbol identity, references, callers, hierarchy, or rename scope.

## Source Of Truth

- Rust CLI command tree: `kast --help`, `kast <command> --help`, and
  `docs/reference/cli-cheat-sheet.md`.
- JSON-RPC method schemas: `references/commands.json`.
- Bootstrap and binary resolution: `scripts/kast-session-start.sh` and
  `scripts/resolve-kast.sh`.
- Skill maintenance assets: `fixtures/maintenance/`; do not load them during
  ordinary Kotlin work.
