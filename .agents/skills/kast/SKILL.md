---
name: kast
description: >
  Use this skill for any Kotlin/JVM semantic code intelligence task: resolve a
  symbol, find references, expand call hierarchies, run diagnostics, assess
  edit impact, plan a rename, or apply rename edits through the packaged
  wrapper scripts. Triggers on: "resolve symbol", "find references",
  "call hierarchy", "who calls", "incoming callers", "outgoing callers",
  "kast", "rename symbol", "run diagnostics", "apply edits", "symbol at
  offset", "semantic analysis", "kotlin analysis daemon". Every multi-step
  operation goes through `scripts/` and emits structured JSON on stdout.
---

# Kast skill

kast is a Kotlin semantic analysis daemon. This skill wraps the CLI in
structured scripts so the agent can stay on JSON instead of brittle shell
pipelines.

## 0. Core principle

Never interact with raw terminal output for workflows that already have a
wrapper. Every multi-step kast operation goes through a script in `scripts/`.
Each wrapper emits structured JSON on stdout, writes raw stderr and daemon
notes to `log_file`, and cleans up its temp files on exit. Read the wrapper
JSON first. Open `log_file` only when `ok=false` or you need daemon notes.

## 1. Bootstrap (run once per session)

Locate the skill root first. Resolve the raw `kast` binary only for commands
that do not have a wrapper yet.

```bash
SKILL_ROOT="$(cd "$(dirname "$(find "$(git rev-parse --show-toplevel)" \
  -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit)")" && pwd)"
KAST="$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")"
```

`$SKILL_ROOT` is the packaged skill root. The wrappers resolve `kast`
internally, so you do not need temp files, redirects, or manual stderr
capture.

Optional: prewarm the workspace when you want a separate readiness step before
you call a wrapper.

```bash
"$KAST" workspace ensure --workspace-root="$(git rev-parse --show-toplevel)"
```

If `workspace ensure` fails, read
`$(git rev-parse --show-toplevel)/.kast/logs/standalone-daemon.log` before you
retry.

## 2. Symbol lookup

Resolve a named symbol with the wrapper. It handles declaration search, UTF-16
offset discovery, `symbol resolve`, and identity confirmation.

```bash
bash "$SKILL_ROOT/scripts/kast-resolve.sh" \
  --workspace-root="$(git rev-parse --show-toplevel)" \
  --symbol=AnalysisServer
```

Add `--file=...`, `--kind=class|function|property`, or
`--containing-type=OuterType` when the human reference is ambiguous.

## 3. Analysis commands

Use the wrappers for every multi-step workflow the skill already covers.

### Resolve a symbol

Use `kast-resolve.sh` when the user gives a symbol name instead of a raw file
offset.

```bash
bash "$SKILL_ROOT/scripts/kast-resolve.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --file=analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt
```

Key output: `ok`, `symbol`, `file_path`, `offset`, `candidate`, `log_file`

### Find references

Use `kast-references.sh` to resolve the symbol and run `references` in one
step.

```bash
bash "$SKILL_ROOT/scripts/kast-references.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --include-declaration=true
```

Key output: `ok`, `symbol`, `references`, `search_scope`, `declaration`,
`log_file`

### Expand callers or callees

Use `kast-callers.sh` to resolve the symbol and run `call hierarchy` with the
requested direction and depth.

```bash
bash "$SKILL_ROOT/scripts/kast-callers.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --direction=incoming \
  --depth=2
```

Key output: `ok`, `symbol`, `root`, `stats`, `log_file`

### Run diagnostics

Use `kast-diagnostics.sh` when you need structured diagnostics for one or more
files.

```bash
bash "$SKILL_ROOT/scripts/kast-diagnostics.sh" \
  --workspace-root=/absolute/workspace/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

Key output: `ok`, `clean`, `error_count`, `warning_count`, `diagnostics`,
`log_file`

### Assess edit impact

Use `kast-impact.sh` before you change a symbol. It resolves the symbol, finds
references, and can include incoming callers in the same result.

```bash
bash "$SKILL_ROOT/scripts/kast-impact.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --include-callers=true
```

Key output: `ok`, `symbol`, `references`, `search_scope`, optional
`call_hierarchy`, `log_file`

### Rename a symbol safely

Use the one-shot rename wrapper for the full mutation workflow.

```bash
bash "$SKILL_ROOT/scripts/kast-rename.sh" \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName
```

`kast-rename.sh` runs `workspace ensure`, plans the rename, extracts the
apply-request with `kast-plan-utils.py`, applies the edits, runs diagnostics on
affected files, and exits non-zero if any `ERROR` diagnostics remain.

### Raw CLI fallback

Use raw `"$KAST"` only when a wrapper does not exist yet, such as
`type hierarchy`, `semantic insertion-point`, `imports optimize`, or a custom
rename-plan flow. Keep `kast-plan-utils.py` in the loop for rename JSON.

```bash
"$KAST" rename \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName \
  --dry-run=true > /tmp/rename-plan.json

python3 "$SKILL_ROOT/scripts/kast-plan-utils.py" \
  extract-apply-request /tmp/rename-plan.json /tmp/apply-request.json

"$KAST" edits apply \
  --workspace-root=/absolute/workspace/path \
  --request-file=/tmp/apply-request.json
```

## 4. Workflows

Use these wrapper combinations for the common agent tasks.

### Resolve or find references for a named symbol

Start with `kast-resolve.sh` when you only need the declaration. Use
`kast-references.sh` when the next step is a reference list.

### Caller or callee exploration

Use `kast-callers.sh` with `--direction=incoming` for callers and
`--direction=outgoing` for callees. Always read `stats` and any node
`truncation` before you report the tree as complete.

### Pre-edit impact assessment

Use `kast-impact.sh` before you edit a symbol. Treat
`search_scope.exhaustive=false`, `stats.timeoutReached=true`, or any truncation
marker as proof that the result is bounded.

### Post-edit validation

After any code change, run `kast-diagnostics.sh` on the modified files. When
you need a quick contract check for the wrappers themselves, run
`validate-wrapper-json.sh`.

```bash
bash "$SKILL_ROOT/scripts/validate-wrapper-json.sh" \
  "$(git rev-parse --show-toplevel)"
```

## 5. Error reference

Use the wrapper JSON as the first failure surface. The wrapper `message`,
`stage`, and `log_file` tell you whether the failure came from argument
validation, workspace startup, candidate lookup, or the underlying CLI call.

| Error or symptom | Cause | Fix |
| --- | --- | --- |
| `argument_validation` | Missing or invalid wrapper arguments | Fix the wrapper flags and rerun |
| `candidate_search` | No declaration candidate matched the symbol query | Add `--file`, `--kind`, or `--containing-type`, or confirm the symbol exists |
| `workspace_ensure` | The daemon did not become ready | Read `.kast/logs/standalone-daemon.log` before retrying |
| `NOT_FOUND` in `log_file` | Offset landed on the wrong token or the file is not indexed | Re-run `kast-resolve.sh` with a better file hint, or wait for `READY` |
| `CONFLICT` from `kast-rename.sh` | Files changed between plan and apply | Re-run `kast-rename.sh` to generate a fresh plan |
| `clean=false` from `kast-diagnostics.sh` | Diagnostics found `ERROR` results | Fix the errors, then rerun diagnostics |

## 6. Rules

- Always use the wrapper scripts for multi-step operations.
- Use raw `kast` CLI only when a wrapper does not exist yet.
- Keep `--key=value` syntax for raw CLI calls.
- Use absolute `--workspace-root`, `--file-path`, and `--file-paths` values
  for raw CLI calls.
- Use `kast-plan-utils.py` for rename-plan JSON. Never use `jq`.
- Treat `search_scope.exhaustive=false`, `stats.timeoutReached=true`,
  `stats.maxTotalCallsReached=true`, `stats.maxChildrenPerNodeReached=true`,
  or node `truncation` as proof that the result is bounded.
- Read the wrapper `log_file` before you retry a workspace-startup failure.
- Never claim a symbol match, reference list, or call tree is complete unless
  the wrapper result supports that claim.

## 7. Integration

Use the narrowest tool that owns the task.

| Task | Tool |
| --- | --- |
| Resolve a symbol name to a real declaration | `kast-resolve.sh` |
| Find references for a named symbol | `kast-references.sh` |
| Explore callers or callees for a named symbol | `kast-callers.sh` |
| Assess pre-edit impact | `kast-impact.sh` |
| Run structured diagnostics for changed files | `kast-diagnostics.sh` |
| Rename a symbol end to end | `kast-rename.sh` |
| Build the project | `kotlin-gradle-loop` skill or targeted Gradle tasks |
| Run tests | `kotlin-gradle-loop` skill or targeted Gradle tasks |
