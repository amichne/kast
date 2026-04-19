---
name: kast
description: >
  Use this skill for Kotlin/JVM semantic analysis and mutation tasks through the
  native `kast skill ...` subcommands. Triggers on: "resolve symbol", "find
  references", "call hierarchy", "who calls", "incoming callers", "outgoing
  callers", "kast", "rename symbol", "run diagnostics", "apply edits",
  "symbol at offset", "semantic analysis", "kotlin analysis daemon",
  "workspace status", "capabilities".
---

# Kast skill

This file is the **sole source of truth** for the packaged kast skill.

The old shell wrapper layer is gone. Every supported workflow now goes through
the native `kast skill ...` commands exposed by the kast CLI. The only shell
entrypoint kept in the skill tree is `scripts/resolve-kast.sh`, which exists
only to locate the binary.

## 1. Bootstrap

Resolve the kast binary once per session:

```bash
SKILL_ROOT="$(cd "$(dirname "$(find "$(git rev-parse --show-toplevel)" \
  -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit)")" && pwd)"
KAST="$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")"
```

After that, call `"$KAST"` directly.

## 2. Input and output contract

All `kast skill` subcommands accept **exactly one argument**:

1. an inline JSON object literal, or
2. a path to a `.json` request file.

Request/response schemas live in `references/wrapper-openapi.yaml`.

`workspaceRoot` is optional in request bodies. Resolution order is:

1. explicit `workspaceRoot`
2. `KAST_WORKSPACE_ROOT` environment variable

If neither is set, the command fails with a validation error. There is no
implicit `git rev-parse` fallback — this is intentional so agents always know
exactly which workspace they are targeting.

Responses are the same `ok`-keyed JSON payloads used by the former wrappers,
including `type` and `log_file`.

## 3. Supported commands

| Intent | Command |
| --- | --- |
| Resolve a symbol | `"$KAST" skill resolve '{...}'` |
| Find references | `"$KAST" skill references '{...}'` |
| Expand callers/callees | `"$KAST" skill callers '{...}'` |
| Run diagnostics | `"$KAST" skill diagnostics '{...}'` |
| Rename a symbol | `"$KAST" skill rename '{...}'` |
| Gather scaffold context | `"$KAST" skill scaffold '{...}'` |
| Apply code and validate | `"$KAST" skill write-and-validate '{...}'` |
| List workspace files | `"$KAST" skill workspace-files '{...}'` |

## 4. Common examples

### Resolve a symbol

```bash
"$KAST" skill resolve \
  '{"symbol":"AnalysisServer","fileHint":"analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt"}'
```

### Find references

```bash
"$KAST" skill references \
  '{"symbol":"AnalysisServer","includeDeclaration":true}'
```

### Expand callers

```bash
"$KAST" skill callers \
  '{"symbol":"AnalysisServer","direction":"incoming","depth":2}'
```

### Run diagnostics

```bash
"$KAST" skill diagnostics \
  '{"filePaths":["/absolute/path/to/File.kt"]}'
```

### Rename a symbol

```bash
"$KAST" skill rename \
  '{"symbol":"OldName","newName":"NewName"}'
```

### Scaffold implementation context

```bash
"$KAST" skill scaffold \
  '{"targetFile":"/absolute/path/to/Interface.kt","targetSymbol":"MyInterface","mode":"implement"}'
```

### Apply code and validate

```bash
"$KAST" skill write-and-validate \
  '{"mode":"create-file","filePath":"/absolute/path/to/NewImpl.kt","content":"..."}'
```

### List workspace files

```bash
"$KAST" skill workspace-files \
  '{"includeFiles":true}'
```

## 5. Evaluation

Use the built-in evaluator for regression tracking:

```bash
"$KAST" eval skill --skill-dir="$SKILL_ROOT"
"$KAST" eval skill --skill-dir="$SKILL_ROOT" --format=markdown
"$KAST" eval skill --skill-dir="$SKILL_ROOT" --compare=baseline.json
```

## 6. Rules

- Use `kast skill ...` for every workflow covered above.
- Use raw `kast` commands only when no `kast skill` subcommand exists.
- Do not resurrect the deleted shell wrapper layer.
- Read `log_file` only when `ok=false` or daemon notes matter.
- Do not use `grep`/`rg`/manual parsing for Kotlin semantic identity.

## 7. Quick routing

| Task | Preferred command |
| --- | --- |
| Explore structure | `kast skill workspace-files`, `kast skill scaffold` |
| Confirm a declaration | `kast skill resolve` |
| Measure scope | `kast skill references`, `kast skill callers` |
| Edit Kotlin | `kast skill write-and-validate`, `kast skill rename` |
| Validate final state | `kast skill diagnostics` |
