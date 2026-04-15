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

### Scaffold context for code generation

Use `kast-scaffold.sh` to gather everything an LLM needs to generate correct code for a symbol:
outline, type hierarchy, references, insertion point, and surrounding file content — in one call.
This replaces manually chaining `outline`, `type-hierarchy`, `references`, and `insertion-point`.

```bash
bash "$SKILL_ROOT/scripts/kast-scaffold.sh" \
  --workspace-root=/absolute/workspace/path \
  --target-file=/absolute/path/to/Interface.kt \
  --target-symbol=MyInterface \
  --mode=implement
```

Modes: `implement` (new impl), `replace` (overwrite a declaration), `consolidate` (merge two into one),
`extract` (pull a nested declaration out).

Add `--kind=class|interface|function|property` to restrict symbol resolution.

Key output fields: `ok`, `outline`, `type_hierarchy`, `references`, `insertion_point` (with
`offset`, `startOffset`, `endOffset`), `file_content`, `log_file`

Use `insertion_point.offset` as `--offset` for insert-at-offset writes, or
`insertion_point.startOffset`/`endOffset` for replace-range writes.

**When to use vs. atomic wrappers:**
- Use `kast-scaffold.sh` when you need full context for code generation (it's a single call).
- Use `kast-resolve.sh` + `kast-references.sh` individually only when you need one specific signal
  and don't need the full scaffold payload.

### Write generated code and validate

Use `kast-write-and-validate.sh` to apply LLM-generated code, clean up imports, and confirm
correctness with diagnostics — in one atomic workflow. Returns `ok=true` only when diagnostics
are clean.

```bash
# Create a new file
bash "$SKILL_ROOT/scripts/kast-write-and-validate.sh" \
  --workspace-root=/absolute/workspace/path \
  --mode=create-file \
  --file-path=/absolute/path/to/NewImpl.kt \
  --content="..."

# Insert at a character offset
bash "$SKILL_ROOT/scripts/kast-write-and-validate.sh" \
  --workspace-root=/absolute/workspace/path \
  --mode=insert-at-offset \
  --file-path=/absolute/path/to/File.kt \
  --offset=1234 \
  --content="..."

# Replace a character range (use startOffset/endOffset from kast-scaffold.sh)
bash "$SKILL_ROOT/scripts/kast-write-and-validate.sh" \
  --workspace-root=/absolute/workspace/path \
  --mode=replace-range \
  --file-path=/absolute/path/to/File.kt \
  --start-offset=100 \
  --end-offset=500 \
  --content="..."
```

Use `--content-file=/path/to/file` instead of `--content` for large payloads.

Key output fields: `ok`, `stage` (where failure occurred: `write`, `optimize_imports`, `diagnostics`),
`import_changes`, `diagnostics` (with `clean`, `error_count`), `log_file`

If `ok=false`, read `stage` and `diagnostics.errors` to identify what to fix before resubmitting.

**When to use vs. direct file writes:**
- Always use `kast-write-and-validate.sh` for Kotlin files — it handles import cleanup and validation.
- Direct file writes bypass optimize-imports and diagnostics; use them only for non-Kotlin files.

### List workspace modules and files

Use `kast-workspace-files.sh` to enumerate workspace modules with their source roots and dependency
relationships. Replaces `find`/`ls`/`tree` for Kotlin file discovery.

```bash
# List all modules (no file enumeration)
bash "$SKILL_ROOT/scripts/kast-workspace-files.sh" \
  --workspace-root=/absolute/workspace/path

# List all modules with individual .kt file paths
bash "$SKILL_ROOT/scripts/kast-workspace-files.sh" \
  --workspace-root=/absolute/workspace/path \
  --include-files=true

# Filter to a single module
bash "$SKILL_ROOT/scripts/kast-workspace-files.sh" \
  --workspace-root=/absolute/workspace/path \
  --module-name=analysis-api \
  --include-files=true
```

Key output fields: `ok`, `modules` (array of `WorkspaceModule` with `name`, `sourceRoots`,
`dependencyModuleNames`, `files`, `fileCount`), `log_file`

`files` is populated only when `--include-files=true`. `fileCount` is always present.

**When to use vs. raw `kast workspace files`:**
- Use `kast-workspace-files.sh` for all agent-driven module/file discovery — it wraps the raw
  command with standard `ok`-keyed JSON, error handling, and log management.
- Use raw `kast workspace files` only when debugging the wrapper itself.

 The following commands are still
available directly but are now called internally by composite wrappers — prefer the wrappers:

- `type-hierarchy` and `insertion-point` — called internally by `kast-scaffold.sh`
- `optimize-imports` — called internally by `kast-write-and-validate.sh`

If you need these primitives directly (for example, in a custom flow that the composite wrappers
don't cover), use:

```bash
"$KAST" type-hierarchy \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --direction=both

"$KAST" insertion-point \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt \
  --target-symbol=MyInterface

"$KAST" optimize-imports \
  --workspace-root=/absolute/workspace/path \
  --file-path=/absolute/path/to/File.kt
```

For custom rename-plan flows, keep `kast-plan-utils.py` in the loop:

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

## 7. "Write a New X" Workflows

Use `kast-scaffold.sh` + `kast-write-and-validate.sh` for LLM-driven code
generation. Kast provides structural context and compiler validation; the LLM
generates the code.

### Implements Y (new implementation of an interface or abstract class)

```
kast-scaffold.sh --workspace-root=… --target-file=Y.kt --target-symbol=Y --mode=implement
  → emit context to LLM
  → LLM generates NewImpl.kt content
kast-write-and-validate.sh --workspace-root=… --mode=create-file --file-path=NewImpl.kt --content=…
  → ok=true when diagnostics are clean
```

### Replaces Z (rewrite a declaration in place)

```
kast-scaffold.sh --workspace-root=… --target-file=Z.kt --target-symbol=Z --mode=replace
  → emit context (includes insertion_point with startOffset/endOffset of the declaration)
  → LLM generates replacement content
kast-write-and-validate.sh --workspace-root=… --mode=replace-range \
  --file-path=Z.kt --start-offset=… --end-offset=… --content=…
```

### Consolidates Y1+Y2 (merge two declarations into one)

```
kast-scaffold.sh … --target-symbol=Y1 --mode=consolidate
kast-scaffold.sh … --target-symbol=Y2 --mode=consolidate
  → LLM generates consolidated Merged.kt
kast-write-and-validate.sh --mode=create-file --file-path=Merged.kt --content=…
  → then use kast-rename.sh to migrate all references
```

### Extracts Y3 from Z3 (pull out a nested declaration)

```
kast-scaffold.sh --target-symbol=Z3 --mode=extract
  → LLM generates: (a) extracted Y3.kt, (b) modified Z3.kt with Y3 removed
kast-write-and-validate.sh --mode=create-file --file-path=Y3.kt --content=…
kast-write-and-validate.sh --mode=replace-range --file-path=Z3.kt --start-offset=… --end-offset=… --content=…
```

**Rules for write-and-validate:**

- Always check `ok` first. If `ok=false`, read `diagnostics.errors` and fix
  before resubmitting.
- `import_changes > 0` means optimize-imports removed or inserted lines; this
  is expected and correct.
- After a `create-file` write, the daemon automatically refreshes the new
  file. You do not need a separate `workspace refresh` call.
- Use `insertion_point.offset` from scaffold output as the `--offset` for
  insert-at-offset, or `insertion_point.startOffset`/`endOffset` for
  replace-range.

## 8. Rules

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

## 9. Integration

Use the narrowest tool that owns the task.

| Task | Tool |
| --- | --- |
| Resolve a symbol name to a real declaration | `kast-resolve.sh` |
| Find references for a named symbol | `kast-references.sh` |
| Explore callers or callees for a named symbol | `kast-callers.sh` |
| Assess pre-edit impact | `kast-impact.sh` |
| Run structured diagnostics for changed files | `kast-diagnostics.sh` |
| Rename a symbol end to end | `kast-rename.sh` |
| Scaffold full symbol context (outline + hierarchy + refs + insertion) | `kast-scaffold.sh` |
| Apply generated code and validate with diagnostics | `kast-write-and-validate.sh` |
| List workspace modules and Kotlin files | `kast-workspace-files.sh` |
| Check daemon health and state | `kast workspace status` (raw CLI) |
| Confirm available capabilities | `kast capabilities` (raw CLI) |
| Smoke-test the skill wrappers | `validate-wrapper-json.sh` |
| Build the project | `kotlin-gradle-loop` skill or targeted Gradle tasks |
| Run tests | `kotlin-gradle-loop` skill or targeted Gradle tasks |
