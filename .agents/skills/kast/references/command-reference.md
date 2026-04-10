# Kast Command Reference

Full syntax, JSON schemas, and request-file formats for all public kast commands.

> **Output redirection:** The JSON-returning command examples below show bare
> invocations for readability. In practice, redirect them per the SKILL.md
> bootstrap pattern: `> "$KAST_RESULT" 2> "$KAST_STDERR"`. Read
> `$KAST_RESULT` for the JSON result. When the exit code is non-zero, read
> `$KAST_STDERR` for the error. On success, `$KAST_STDERR` may still contain a
> daemon note, such as an auto-start message or `state: INDEXING`. `kast smoke`
> follows the same JSON-first contract by default and only switches to
> human-readable markdown when you pass `--format=markdown`.

---

## Common Options

Every command accepts:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--workspace-root=` | absolute path | required | Workspace root to analyze |
| `--wait-timeout-ms=` | integer ms | 60000 | Max wait for the daemon state required by the command |
| `--request-file=` | absolute path | — | JSON request payload on disk |

All options use `--key=value` syntax. No positional arguments.

Runtime-dependent read, mutation, and refresh commands also accept
`--no-auto-start=true`. `workspace ensure` accepts `--accept-indexing=true`
and still waits for `READY` by default.

---

## Workspace Commands

### `workspace status`

Inspect daemon descriptors, liveness, and readiness.

```
kast workspace status --workspace-root=/absolute/path
```

**Output — `WorkspaceStatusResult`:**

- `workspaceRoot` — absolute workspace root
- `descriptorDirectory` — absolute `~/.config/kast/daemons` directory
- `selected` — the preferred `RuntimeCandidateStatus`, or `null`
- `candidates` — every registered `RuntimeCandidateStatus` for the workspace

Each `RuntimeCandidateStatus` includes:

- `descriptorPath` and the parsed `descriptor`
- `pidAlive`, `reachable`, and `ready`
- optional `runtimeStatus`, including `state`
  (`STARTING` | `INDEXING` | `READY` | `DEGRADED`)
- optional `capabilities`
- optional `errorMessage`

**Errors:** None specific. Exit 0 with `selected = null` and an empty
`candidates` array if no descriptor exists.

---

### `workspace ensure`

Explicitly prewarm or reuse a standalone daemon.

```
kast workspace ensure \
  --workspace-root=/absolute/path \
  [--wait-timeout-ms=60000] \
  [--accept-indexing=true]
```

By default, `workspace ensure` waits for `READY`. Add
`--accept-indexing=true` to return once the daemon is servable in `INDEXING`.

**Output — `WorkspaceEnsureResult`:**

- `workspaceRoot`
- `started` — `true` when this command launched a new daemon
- optional `logFile`
- `selected` — the chosen `RuntimeCandidateStatus`

**Errors:**
- Timeout exceeded: exit non-zero, message on stderr.
- `DEGRADED` daemon: `ensure` will attempt to restart.

---

### `workspace stop`

Stop the registered daemon for a workspace and remove its descriptor.

```
kast workspace stop --workspace-root=/absolute/path
```

**Output — `DaemonStopResult`** with `stopped`, optional `descriptorPath`, and
optional `pid`.

**Errors:** Exit non-zero if no daemon is registered.

---

## `capabilities`

Print the capability set of a servable daemon.

```
kast capabilities \
  --workspace-root=/absolute/path \
  [--wait-timeout-ms=60000]
```

This command auto-starts the daemon when needed unless you add
`--no-auto-start=true`.

**Output — `BackendCapabilities`:**

```json
{
  "backendName": "kotlin-analysis",
  "backendVersion": "1.0.0",
  "workspaceRoot": "/absolute/path",
  "readCapabilities": [
    "RESOLVE_SYMBOL",
    "FIND_REFERENCES",
    "CALL_HIERARCHY",
    "DIAGNOSTICS"
  ],
  "mutationCapabilities": ["RENAME", "APPLY_EDITS"],
  "limits": {
    "maxResults": 500,
    "requestTimeoutMillis": 30000,
    "maxConcurrentRequests": 4
  },
  "schemaVersion": 1
}
```

`readCapabilities` values: `RESOLVE_SYMBOL` | `FIND_REFERENCES` | `CALL_HIERARCHY` | `DIAGNOSTICS`

`mutationCapabilities` values: `RENAME` | `APPLY_EDITS`

**Errors:** `CAPABILITY_NOT_SUPPORTED` if the daemon backend does not advertise the requested capability.

---

## Analysis Commands

### `resolve`

Resolve the symbol at a file offset.

**Inline form:**
```
kast resolve \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123
```

**Request-file form:**
```
kast resolve \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `SymbolQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  }
}
```

`offset` is a zero-based UTF-16 character offset from the start of the file.

**Output — `SymbolResult`:**
```json
{
  "symbol": {
    "fqName": "io.example.MyClass.myFunction",
    "kind": "FUNCTION",
    "location": {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "startLine": 10,
      "startColumn": 4,
      "preview": "fun myFunction()"
    },
    "type": "() -> Unit",
    "containingDeclaration": "io.example.MyClass"
  },
  "schemaVersion": 1
}
```

`kind` values: `CLASS` | `INTERFACE` | `OBJECT` | `FUNCTION` | `PROPERTY` | `CONSTRUCTOR` | `ENUM_ENTRY` | `TYPE_ALIAS` | `PACKAGE` | `PARAMETER` | `LOCAL_VARIABLE` | `UNKNOWN`

`type` and `containingDeclaration` are nullable.

**Errors:** `NOT_FOUND` if offset resolves to whitespace or a non-symbol token. `CAPABILITY_NOT_SUPPORTED` if daemon lacks `RESOLVE_SYMBOL`.

---

### `references`

Find all references to the symbol at a file offset.

**Inline form:**
```
kast references \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  [--include-declaration=true]
```

**Request-file form:**
```
kast references \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `ReferencesQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  },
  "includeDeclaration": false
}
```

**Output — `ReferencesResult`:**
```json
{
  "declaration": null,
  "references": [
    {
      "filePath": "/absolute/path/to/Other.kt",
      "startOffset": 450,
      "endOffset": 460,
      "startLine": 30,
      "startColumn": 8,
      "preview": "val x = myFunction()"
    }
  ],
  "page": {
    "truncated": false,
    "nextPageToken": null
  },
  "schemaVersion": 1
}
```

`declaration` is `Symbol | null` — populated when `includeDeclaration = true`.

`page.truncated = true` means results were capped by `limits.maxResults`. Currently no pagination — truncation is final.

**Errors:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED` (`FIND_REFERENCES`).

---

### `call-hierarchy`

Expand a bounded incoming or outgoing call tree from the symbol at a file
offset.

**Inline form:**
```
kast call-hierarchy \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  --direction=incoming \
  [--depth=3] \
  [--max-total-calls=256] \
  [--max-children-per-node=64] \
  [--timeout-millis=5000]
```

**Request-file form:**
```
kast call-hierarchy \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `CallHierarchyQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  },
  "direction": "INCOMING",
  "depth": 3,
  "maxTotalCalls": 256,
  "maxChildrenPerNode": 64,
  "timeoutMillis": null
}
```

**Output — `CallHierarchyResult`:**
```json
{
  "root": {
    "symbol": {
      "fqName": "io.example.MyClass.myFunction",
      "kind": "FUNCTION",
      "location": {
        "filePath": "/absolute/path/to/File.kt",
        "startOffset": 120,
        "endOffset": 130,
        "startLine": 10,
        "startColumn": 4,
        "preview": "fun myFunction()"
      },
      "type": "() -> Unit",
      "containingDeclaration": "io.example.MyClass"
    },
    "callSite": null,
    "truncation": null,
    "children": []
  },
  "stats": {
    "totalNodes": 1,
    "totalEdges": 0,
    "truncatedNodes": 0,
    "maxDepthReached": 0,
    "timeoutReached": false,
    "maxTotalCallsReached": false,
    "maxChildrenPerNodeReached": false,
    "filesVisited": 1
  },
  "schemaVersion": 1
}
```

`direction` values: `INCOMING` | `OUTGOING`

`callSite` is `Location | null`. It is usually null on the root node and
populated for child edges.

`truncation.reason` values: `CYCLE` | `MAX_TOTAL_CALLS` |
`MAX_CHILDREN_PER_NODE` | `TIMEOUT`

**Errors:** `NOT_FOUND`, `CAPABILITY_NOT_SUPPORTED` (`CALL_HIERARCHY`).

---

### `diagnostics`

Run diagnostics for one or more files.

**Inline form:**
```
kast diagnostics \
  --workspace-root=/absolute/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

**Request-file form:**
```
kast diagnostics \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `DiagnosticsQuery`:**
```json
{
  "filePaths": [
    "/absolute/path/to/A.kt",
    "/absolute/path/to/B.kt"
  ]
}
```

**Output — `DiagnosticsResult`:**
```json
{
  "diagnostics": [
    {
      "location": {
        "filePath": "/absolute/path/to/A.kt",
        "startOffset": 200,
        "endOffset": 215,
        "startLine": 15,
        "startColumn": 4,
        "preview": "val x: String = 42"
      },
      "severity": "ERROR",
      "message": "Type mismatch: inferred type is Int but String was expected",
      "code": "TYPE_MISMATCH"
    }
  ],
  "page": null,
  "schemaVersion": 1
}
```

`severity` values: `ERROR` | `WARNING` | `INFO`

`code` is nullable — may be absent for some diagnostic sources.

Empty `diagnostics` array means the files are clean.

**Errors:** `NOT_FOUND` if a file path does not exist in the workspace. `CAPABILITY_NOT_SUPPORTED` (`DIAGNOSTICS`).

---

### `rename`

Plan a rename operation. By default a dry run — does not write files.

**Inline form:**
```
kast rename \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=123 \
  --new-name=RenamedSymbol \
  [--dry-run=true]
```

**Request-file form:**
```
kast rename \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `RenameQuery`:**
```json
{
  "position": {
    "filePath": "/absolute/path/to/File.kt",
    "offset": 123
  },
  "newName": "RenamedSymbol",
  "dryRun": true
}
```

`dryRun` defaults to `true`. Pass `false` only when you intend to apply edits immediately via `apply-edits`.

**Output — `RenameResult`:**
```json
{
  "edits": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "fileHashes": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "hash": "sha256:abcdef1234567890..."
    }
  ],
  "affectedFiles": [
    "/absolute/path/to/File.kt",
    "/absolute/path/to/Other.kt"
  ],
  "schemaVersion": 1
}
```

`fileHashes` are SHA-256 hashes of each affected file at the time the plan was generated. Pass them unchanged to `apply-edits` to guard against concurrent modification.

**Errors:** `NOT_FOUND`, `VALIDATION_ERROR` (invalid new name), `CAPABILITY_NOT_SUPPORTED` (`RENAME`).

---

### `apply-edits`

Apply a prepared edit plan. Always requires `--request-file`.

```
kast apply-edits \
  --workspace-root=/absolute/path \
  --request-file=/absolute/path/to/query.json
```

**Request file schema — `ApplyEditsQuery`:**
```json
{
  "edits": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "fileHashes": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "hash": "sha256:abcdef1234567890..."
    }
  ]
}
```

Use `rename` output's `edits` and `fileHashes` fields directly as the request body.

**Output — `ApplyEditsResult`:**
```json
{
  "applied": [
    {
      "filePath": "/absolute/path/to/File.kt",
      "startOffset": 120,
      "endOffset": 130,
      "newText": "RenamedSymbol"
    }
  ],
  "affectedFiles": [
    "/absolute/path/to/File.kt"
  ],
  "schemaVersion": 1
}
```

**Errors:**
- `CONFLICT` (409): a file was modified after the rename plan was generated. The `details` map lists affected paths. Re-run `rename` to get a fresh plan.
- `APPLY_PARTIAL_FAILURE` (500): commit phase failed for one or more files. `details` maps file paths to error messages. Files not in `details` were written successfully. Manual inspection required.

---

## Shell Integration

### `completion bash`

Emit a Bash completion script.

```
kast completion bash
```

Source the output to enable tab completion for kast commands and options in Bash.

### `completion zsh`

Emit a Zsh completion script.

```
kast completion zsh
```

Source the output to enable tab completion for kast commands and options in Zsh.

---

## CLI Management

### `install`

Install a portable kast archive as a named local instance.

```
kast install \
  --archive=/absolute/path/to/kast.zip \
  [--instance=my-dev]
```

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--archive=` | absolute path | required | Path to a kast portable zip |
| `--instance=` | string | auto-generated | Instance name for the install |

Installs to `~/.local/share/kast/instances/<name>/` with a launcher at `~/.local/bin/kast-<name>`.

**Output:** Prints the installed instance name and launcher path.

**Errors:** Exit non-zero if the archive is invalid or the instance name contains invalid characters.

---

### `install skill`

Install the packaged `kast` skill into a workspace-local skills directory. The
command copies the bundled skill tree, writes a `.kast-version` marker, and
returns `skipped: true` when the target already matches the current CLI
version.

```bash
kast install skill \
  [--target-dir=/absolute/path/to/skills] \
  [--name=kast] \
  [--yes=true]
```

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--target-dir=` | absolute path | auto-detected | Skills root directory (`.agents/skills`, `.github/skills`, or `.claude/skills`) |
| `--name=` | string | `kast` | Directory name for the installed skill |
| `--link-name=` | string | — | Deprecated alias for `--name=` |
| `--yes=` | boolean | `false` | Overwrite an existing installed skill directory |

**Output — `InstallSkillResult`:**

```json
{
  "installedAt": "/absolute/path/to/workspace/.agents/skills/kast",
  "version": "0.1.1-SNAPSHOT",
  "skipped": false,
  "schemaVersion": 1
}
```

If the target directory already exists and its `.kast-version` matches the
current CLI version, the command returns the same payload with `skipped: true`.
If the target exists with a different version, rerun with `--yes=true` to
overwrite it.

---

## Validation

### `smoke`

Run the portable smoke workflow against a real workspace by invoking the
maintained `smoke.sh` entrypoint through the current `kast` executable. The
default report is aggregated JSON on stdout so agents can consume one compact
result; pass `--format=markdown` when you want a human-friendly report.

```bash
kast smoke \
  [--workspace-root=/absolute/path/to/workspace] \
  [--file=CliCommandCatalog.kt] \
  [--source-set=:kast-cli:test] \
  [--symbol=KastCli] \
  [--format=json]
```

**Options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--workspace-root=` | absolute path | current working directory | Workspace root to smoke-test |
| `--file=` | string | — | Match a declaration file by basename or relative path |
| `--source-set=` | string | — | Match a `:module:sourceSet` key |
| `--symbol=` | string | — | Match a declaration name |
| `--format=` | `json` \| `markdown` | `json` | Render the aggregated smoke report as JSON or markdown |

`kast smoke` picks the current launcher path automatically and passes it to the
shell script as `--kast=`. When you run `smoke.sh` directly, you can still pass
`--kast=` yourself.

**Output:** Progress lines on stderr plus an aggregated readiness report on
stdout. The default stdout shape is JSON.

**Errors:** Exit non-zero when any smoke assertion fails or when the active
filters match no declarations.

---

## Helper Scripts

The packaged kast skill ships wrapper scripts and helper utilities in
`"$SKILL_ROOT/scripts/"` so automation can stay on structured JSON instead of
shell quoting. Set `SKILL_ROOT` to the absolute path of the installed skill
directory (for example `/your/workspace/.agents/skills/kast`).

Each wrapper emits structured JSON on stdout with a top-level `ok` boolean and
`log_file`. The raw `kast` CLI still exists for commands without wrappers, but
the wrappers are the default path for symbol lookup, references, callers,
diagnostics, impact assessment, and full rename flows.

| Script | Purpose | Key output |
| --- | --- | --- |
| `kast-resolve.sh` | Resolve a human symbol query to a confirmed declaration | `symbol`, `file_path`, `offset`, `candidate`, `log_file` |
| `kast-references.sh` | Resolve a symbol query and expand references | `symbol`, `references`, `search_scope`, `declaration`, `log_file` |
| `kast-callers.sh` | Resolve a symbol query and expand incoming or outgoing callers | `symbol`, `root`, `stats`, `log_file` |
| `kast-diagnostics.sh` | Run structured diagnostics on one or more files | `clean`, `error_count`, `warning_count`, `diagnostics`, `log_file` |
| `kast-impact.sh` | Resolve a symbol query, gather references, and optionally gather incoming callers | `references`, `search_scope`, optional `call_hierarchy`, `log_file` |
| `kast-rename.sh` | Run the full rename workflow end to end | `ApplyEditsResult` JSON on stdout; step log on stderr |
| `kast-plan-utils.py` | Extract or inspect rename-plan JSON | Plan metadata or apply-request JSON |
| `find-symbol-offset.py` | Compute declaration-first UTF-16 offsets inside one file | Tab-separated candidates |
| `validate-wrapper-json.sh` | Smoke-test wrapper success and failure JSON contracts | Aggregated validation JSON |

---

### `kast-resolve.sh` — Resolve a human symbol query

Use this wrapper when the user names a symbol instead of giving a file offset.
It searches for declaration candidates, computes UTF-16 offsets with
`find-symbol-offset.py`, runs `resolve`, and confirms the match before
it returns.

```bash
bash "$SKILL_ROOT/scripts/kast-resolve.sh" \
  --workspace-root=/absolute/path \
  --symbol=AnalysisServer \
  --file=analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisServer.kt
```

Optional flags:
- `--file=<absolute path, workspace-relative path, or glob>`
- `--kind=class|function|property`
- `--containing-type=OuterType`

**stdout:** wrapper JSON with `ok`, `symbol`, `file_path`, `offset`,
`candidate`, and `log_file`.

---

### `kast-references.sh` — References from a human symbol query

Use this wrapper when you want the declaration and the reference list in one
call.

```bash
bash "$SKILL_ROOT/scripts/kast-references.sh" \
  --workspace-root=/absolute/path \
  --symbol=AnalysisServer \
  --include-declaration=true
```

Optional flags:
- `--file=<absolute path, workspace-relative path, or glob>`
- `--kind=class|function|property`
- `--containing-type=OuterType`
- `--include-declaration=true|false`

**stdout:** wrapper JSON with `ok`, `symbol`, `references`, `search_scope`,
optional `declaration`, and `log_file`.

---

### `kast-callers.sh` — Incoming or outgoing call hierarchy

Use this wrapper when you need callers or callees from a named symbol query.

```bash
bash "$SKILL_ROOT/scripts/kast-callers.sh" \
  --workspace-root=/absolute/path \
  --symbol=AnalysisServer \
  --direction=incoming \
  --depth=2
```

Optional flags:
- `--file=<absolute path, workspace-relative path, or glob>`
- `--kind=class|function|property`
- `--containing-type=OuterType`
- `--direction=incoming|outgoing`
- `--depth=<non-negative integer>`

**stdout:** wrapper JSON with `ok`, `symbol`, `root`, `stats`, and `log_file`.

---

### `kast-diagnostics.sh` — Structured diagnostics

Use this wrapper when you need diagnostics on one or more files without
managing temp files or parsing stderr.

```bash
bash "$SKILL_ROOT/scripts/kast-diagnostics.sh" \
  --workspace-root=/absolute/path \
  --file-paths=/absolute/A.kt,/absolute/B.kt
```

`--file-paths` accepts comma-separated absolute or workspace-relative paths.

**stdout:** wrapper JSON with `ok`, `clean`, `error_count`, `warning_count`,
`info_count`, `diagnostics`, and `log_file`.

---

### `kast-impact.sh` — Pre-edit impact assessment

Use this wrapper before you change a symbol. It resolves the symbol, expands
references, and can include incoming callers in the same result.

```bash
bash "$SKILL_ROOT/scripts/kast-impact.sh" \
  --workspace-root=/absolute/path \
  --symbol=AnalysisServer \
  --include-callers=true
```

Optional flags:
- `--file=<absolute path, workspace-relative path, or glob>`
- `--kind=class|function|property`
- `--containing-type=OuterType`
- `--include-callers=true|false`

**stdout:** wrapper JSON with `ok`, `symbol`, `references`, `search_scope`,
optional `call_hierarchy`, and `log_file`.

---

### `kast-rename.sh` — One-shot rename workflow

Runs the complete rename workflow (workspace ensure → plan → apply → diagnostics) in a
single invocation. All JSON manipulation is handled by `kast-plan-utils.py`; no `jq`
required. Temp files are created under `mktemp -d` and removed on exit via `trap`.

```bash
bash "$SKILL_ROOT/scripts/kast-rename.sh" \
  --workspace-root=/absolute/path \
  --file-path=/absolute/path/to/File.kt \
  --offset=<offset> \
  --new-name=NewName
```

**All four arguments are required.**

**stdout:** `ApplyEditsResult` JSON (same schema as `apply-edits`).

**stderr:** step-by-step progress lines prefixed with `[kast-rename]`.

**Exit codes:**
- `0` — edits applied, diagnostics clean.
- `1` — ERROR-severity diagnostics found, or apply/plan step failed.

---

### `find-symbol-offset.py` — Declaration-first offsets inside one file

Use this helper when you already know the file and need candidate UTF-16
offsets for a named symbol.

```bash
python3 "$SKILL_ROOT/scripts/find-symbol-offset.py" \
  /absolute/path/to/File.kt \
  --symbol AnalysisServer
```

**stdout:** one candidate per line as
`<offset>\t<line>\t<col>\t<context-snippet>`, with declaration-like matches
first.

---

### `kast-plan-utils.py` — JSON utilities for rename workflows

Processes rename plan JSON and diagnostics result JSON. Avoids all `jq` usage so there
are no shell-quoting edge cases. Invoke with `python3`.

```bash
UTILS="$SKILL_ROOT/scripts/kast-plan-utils.py"
```

**Subcommands:**

| Subcommand | Arguments | stdout | Exit |
|---|---|---|---|
| `extract-apply-request` | `<plan-file> <out-file>` | Writes `{edits, fileHashes}` to `<out-file>` | 0 |
| `affected-files-csv` | `<plan-file>` | Comma-separated absolute paths of affected files | 0 |
| `affected-files-list` | `<plan-file>` | One absolute file path per line | 0 |
| `count-edits` | `<plan-file>` | Number of edits in the plan | 0 |
| `check-diagnostics` | `<diagnostics-result-file>` | ERROR count; ERROR details on stderr | 0 if clean, 1 if errors |

`<plan-file>` is the JSON output of `kast rename --dry-run=true`.

`<diagnostics-result-file>` is the JSON output of `kast diagnostics`.

**Usage examples:**

```bash
PLAN=/tmp/rename-plan.json
UTILS="$SKILL_ROOT/scripts/kast-plan-utils.py"

# Inspect the plan before applying
python3 "$UTILS" count-edits          "$PLAN"   # → 8
python3 "$UTILS" affected-files-list  "$PLAN"   # → one path per line

# Build the apply-request file (no jq, no inline JSON)
python3 "$UTILS" extract-apply-request "$PLAN" /tmp/apply-req.json

# Check diagnostics after applying
"$KAST" diagnostics \
  --workspace-root=/absolute/path \
  --file-paths="$(python3 "$UTILS" affected-files-csv "$PLAN")" \
  > "$KAST_RESULT" 2> "$KAST_STDERR"
python3 "$UTILS" check-diagnostics "$KAST_RESULT" || echo "Errors found — see stderr"
```

---

### `validate-wrapper-json.sh` — Smoke validation for wrapper contracts

Use this helper when you want a quick check that each wrapper still emits valid
JSON on both success and failure paths.

```bash
bash "$SKILL_ROOT/scripts/validate-wrapper-json.sh" \
  /absolute/path/to/workspace
```

The script creates a temporary sample Kotlin workspace, runs the wrappers
against that workspace, and emits aggregated validation JSON on stdout. If you
want `resolve-kast.sh` to prefer a local checkout, pass that source root as the
positional argument.

---

## Error Response Format

Raw CLI error payloads can surface on stdout or stderr depending on the command
path and runtime state. The wrappers normalize that surface by always writing
raw notes to `log_file` and wrapper JSON to stdout. When you drop to raw CLI,
expect the same `ApiErrorResponse` shape even if it arrives on stderr:

```json
{
  "schemaVersion": 1,
  "requestId": "req-abc123",
  "code": "NOT_FOUND",
  "message": "No symbol found at offset 123 in File.kt",
  "retryable": false,
  "details": {}
}
```

| `code` | HTTP | `retryable` | Meaning |
|--------|------|-------------|---------|
| `VALIDATION_ERROR` | 400 | false | Bad request parameters |
| `UNAUTHORIZED` | 401 | false | Invalid auth token |
| `NOT_FOUND` | 404 | false | Symbol/file not found |
| `CONFLICT` | 409 | false | File modified since plan; re-plan needed |
| `CAPABILITY_NOT_SUPPORTED` | 501 | false | Backend lacks this capability |
| `APPLY_PARTIAL_FAILURE` | 500 | false | Partial write failure; inspect details |
