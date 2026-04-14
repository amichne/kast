---
name: kast
description: >
  Use this skill for any Kotlin/JVM semantic code intelligence task: resolve a
  symbol, find references, expand call hierarchies, run diagnostics, assess
  edit impact, rename a symbol, or check workspace health — all through
  structured wrapper scripts that emit `ok`-keyed JSON. Triggers on: "resolve
  symbol", "find references", "call hierarchy", "who calls", "incoming
  callers", "outgoing callers", "kast", "rename symbol", "run diagnostics",
  "apply edits", "symbol at offset", "semantic analysis",
  "kotlin analysis daemon", "workspace status", "capabilities".
---

# Kast skill

Kast is a Kotlin semantic analysis daemon. This skill wraps the CLI in
structured scripts so the agent stays on JSON instead of brittle shell
pipelines.

## 0. Core principle

Never interact with raw terminal output for workflows that already have a
wrapper. Every multi-step kast operation goes through a script in `scripts/`.
Each wrapper emits structured JSON on stdout, writes raw stderr and daemon
notes to `log_file`, and cleans up its temp files on exit. Read the wrapper
JSON first. Open `log_file` only when `ok=false` or you need daemon notes.

## 1. Bootstrap (run once per session)

Locate the skill root and resolve the kast binary before calling any wrapper:

```bash
SKILL_ROOT="$(cd "$(dirname "$(find "$(git rev-parse --show-toplevel)" \
  -name SKILL.md -path "*/kast/SKILL.md" -maxdepth 6 -print -quit)")" && pwd)"
KAST="$(bash "$SKILL_ROOT/scripts/resolve-kast.sh")"
```

`$SKILL_ROOT` is the packaged skill root. The wrappers resolve `kast`
internally, so you do not need to pass `$KAST` explicitly to them.

**Optional prewarm** — run this when you want an explicit readiness check
before calling any wrapper:

```bash
"$KAST" workspace ensure --workspace-root="$(git rev-parse --show-toplevel)"
```

If `workspace ensure` fails, read the daemon log at
`$KAST_CONFIG_HOME/logs/<hash>/standalone-daemon.log` (defaults to
`~/.config/kast/logs/<hash>/standalone-daemon.log`, where `<hash>` is the
first 12 characters of the SHA-256 of the absolute workspace root) before
retrying. See `references/troubleshooting.md` for decision trees.

## 2. Symbol lookup

Resolve a named symbol with the wrapper. It handles declaration search,
UTF-16 offset discovery, `resolve`, and identity confirmation.

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

Use `kast-callers.sh` to resolve the symbol and run `call-hierarchy` with the
requested direction and depth.

```bash
bash "$SKILL_ROOT/scripts/kast-callers.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --direction=incoming \
  --depth=2
```

Optional tuning flags (passed through to the underlying CLI):
`--max-total-calls=256`, `--max-children-per-node=64`,
`--timeout-millis=5000`

Key output: `ok`, `symbol`, `root`, `stats`, `log_file`

### Run diagnostics

Use `kast-diagnostics.sh` when you need structured diagnostics for one or
more files.

```bash
bash "$SKILL_ROOT/scripts/kast-diagnostics.sh" \
  --workspace-root=/absolute/workspace/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

Key output: `ok`, `clean`, `error_count`, `warning_count`, `info_count`,
`diagnostics`, `log_file`

### Assess edit impact

Use `kast-impact.sh` before you change a symbol. It resolves the symbol, finds
references, and can include incoming callers in the same result.

```bash
bash "$SKILL_ROOT/scripts/kast-impact.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=AnalysisServer \
  --include-callers=true \
  --caller-depth=2
```

Key output: `ok`, `symbol`, `references`, `search_scope`, optional
`call_hierarchy`, `log_file`

### Rename a symbol safely

Use the one-shot rename wrapper for the full mutation workflow. It accepts
either a symbol name (recommended) or a precise file-path and offset.

**Symbol mode (recommended — resolves the symbol first):**

```bash
bash "$SKILL_ROOT/scripts/kast-rename.sh" \
  --workspace-root=/absolute/workspace/path \
  --symbol=OldName \
  --new-name=NewSymbolName
```

**Offset mode (when exact position is already known):**

```bash
bash "$SKILL_ROOT/scripts/kast-rename.sh" \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewSymbolName
```

`kast-rename.sh` runs workspace ensure (or symbol resolution), plans the
rename, extracts the apply-request with `kast-plan-utils.py`, applies the
edits, runs diagnostics on affected files, and exits non-zero if any `ERROR`
diagnostics remain.

Key output: `ok`, `query`, `edit_count`, `affected_files`, `apply_result`,
`diagnostics`, `log_file`

### Raw CLI fallback

Use raw `"$KAST"` only when a wrapper does not exist yet, such as
`type-hierarchy`, `insertion-point`, `optimize-imports`, or a custom
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

"$KAST" apply-edits \
  --workspace-root=/absolute/workspace/path \
  --request-file=/tmp/apply-request.json
```

## 4. Workspace and daemon lifecycle

Use these raw CLI commands to manage workspace state. They do not have
wrappers; call `$KAST` directly.

### Check daemon state

```bash
"$KAST" workspace status --workspace-root=/absolute/workspace/path
```

Prints `RuntimeCandidateStatus` for every registered daemon, including:
`state` (`STARTING` | `INDEXING` | `READY` | `DEGRADED`), `pidAlive`,
`reachable`, `ready`, and `capabilities`.

### Wait for the daemon to be ready

```bash
"$KAST" workspace ensure --workspace-root=/absolute/workspace/path
```

Waits for `READY` by default. Add `--accept-indexing=true` to return as soon
as the daemon is servable (state `INDEXING` or better).

### Stop the daemon

```bash
"$KAST" workspace stop --workspace-root=/absolute/workspace/path
```

### Check what capabilities are available

```bash
"$KAST" capabilities --workspace-root=/absolute/workspace/path
```

Prints `readCapabilities` and `mutationCapabilities`. Run this when a wrapper
returns `CAPABILITY_NOT_SUPPORTED` to confirm what the backend supports.

### Daemon states

| State | Meaning | Safe to query? |
| --- | --- | --- |
| `STARTING` | JVM starting; workspace not yet bootstrapped | No |
| `INDEXING` | Background index build running; partial results possible | With `--accept-indexing=true` |
| `READY` | Fully indexed; all queries return stable results | Yes |
| `DEGRADED` | Unhealthy; `workspace ensure` will attempt restart | No |

When `workspace ensure` times out, always read the daemon log before retrying.
`selected: null` in `workspace status` means startup failed silently — not
that no daemon is configured.

### Smoke validation

```bash
"$KAST" smoke --workspace-root=/absolute/workspace/path [--format=json]
```

Runs the portable smoke workflow and emits aggregated JSON on stdout. Use this
after install or when the wrappers behave unexpectedly.

For a quick wrapper-contract check (success and failure paths for every
wrapper), run:

```bash
bash "$SKILL_ROOT/scripts/validate-wrapper-json.sh" \
  "$(git rev-parse --show-toplevel)"
```

## 5. Workflows

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
`search_scope.exhaustive=false`, `stats.timeoutReached=true`, or any
truncation marker as proof that the result is bounded — do not claim
completeness.

### Post-edit validation

After any code change, run `kast-diagnostics.sh` on the modified files. A
clean result (`clean=true`, `error_count=0`) is required before reporting
success to the user.

### Full rename end-to-end

Use `kast-rename.sh --symbol=X --new-name=Y` for agent-driven renames. Check
`ok` and `diagnostics.clean` in the wrapper JSON result. If `ok=false`,
inspect `stage` and `log_file` to identify where the workflow failed.

## 6. Error reference

Use the wrapper JSON as the first failure surface. The wrapper `message`,
`stage`, and `log_file` tell you whether the failure came from argument
validation, workspace startup, candidate lookup, or the underlying CLI call.

| Error or symptom | Cause | Fix |
| --- | --- | --- |
| `argument_validation` | Missing or invalid wrapper arguments | Fix the wrapper flags and rerun |
| `candidate_search` | No declaration candidate matched the symbol query | Add `--file`, `--kind`, or `--containing-type`, or confirm the symbol exists |
| `workspace_ensure` | The daemon did not become ready | Read the daemon log before retrying |
| `symbol_resolve` | No resolved symbol matched after candidate search | Try a more precise file hint or kind |
| `NOT_FOUND` in `log_file` | Offset landed on the wrong token or file not indexed | Re-run `kast-resolve.sh` with a better hint, or wait for `READY` |
| `CONFLICT` from `apply-edits` | Files changed between plan and apply | Re-run `kast-rename.sh` to generate a fresh plan |
| `APPLY_PARTIAL_FAILURE` | Commit phase failed for some files | Inspect `details` map; files not in `details` were written |
| `clean=false` from `kast-diagnostics.sh` | ERROR-severity diagnostics found | Fix the errors, then rerun diagnostics |
| `CAPABILITY_NOT_SUPPORTED` | Backend lacks the requested operation | Run `kast capabilities` to see what is available |

See `references/troubleshooting.md` for full decision trees.

## 7. Rules

- Always use the wrapper scripts for multi-step operations.
- Use raw `kast` CLI only when a wrapper does not exist yet.
- Keep `--key=value` syntax for raw CLI calls.
- Use absolute `--workspace-root`, `--file-path`, and `--file-paths` values
  for raw CLI calls.
- Use `kast-plan-utils.py` for rename-plan JSON. Never use `jq`.
- Treat `search_scope.exhaustive=false`, `stats.timeoutReached=true`,
  `stats.maxTotalCallsReached=true`, `stats.maxChildrenPerNodeReached=true`,
  or node `truncation` as proof that the result is bounded.
- Treat `page.truncated=true` in a `references` result as proof that the
  reference list is incomplete — do not claim exhaustive coverage.
- Read the wrapper `log_file` before you retry a workspace-startup failure.
- Never claim a symbol match, reference list, or call tree is complete unless
  the wrapper result explicitly supports that claim.
- Wait for `state = READY` (not just `INDEXING`) before trusting semantic
  results in a newly started daemon.

## 8. Integration

Use the narrowest tool that owns the task.

| Task | Tool |
| --- | --- |
| Resolve a symbol name to a real declaration | `kast-resolve.sh` |
| Find references for a named symbol | `kast-references.sh` |
| Explore callers or callees for a named symbol | `kast-callers.sh` |
| Assess pre-edit impact | `kast-impact.sh` |
| Run structured diagnostics for changed files | `kast-diagnostics.sh` |
| Rename a symbol end to end | `kast-rename.sh` |
| Check daemon health and state | `kast workspace status` (raw CLI) |
| Confirm available capabilities | `kast capabilities` (raw CLI) |
| Smoke-test the skill wrappers | `validate-wrapper-json.sh` |
| Build the project | `kotlin-gradle-loop` skill or targeted Gradle tasks |
| Run tests | `kotlin-gradle-loop` skill or targeted Gradle tasks |
