# Kast quickstart

## Put `kast` on PATH

The ideal setup is boring: `command -v kast` succeeds and agents run `kast`
directly. Prefer a durable PATH install such as Homebrew or the managed
`~/.local/bin/kast` shim over per-command absolute paths.

```console
command -v kast
kast --help
```

If `kast` is missing in an installed skill session, stop and report that setup
blocker instead of switching to non-semantic Kotlin search. Use
`kast doctor --repair` for broad install repair and manifest convergence.

If `kast` exists but a command reports `NO_BACKEND_AVAILABLE`,
`INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a missing source-index
database, warm the IDEA backend before using text fallback:

```console
kast up --workspace-root "$PWD" --backend idea
```

Kast opens IDEA or Android Studio dynamically only when
`runtime.ideaLaunch.enabled` allows it. If launch is not enabled, the command
reports that the project must be opened in the IDE with the Kast plugin
installed. That is the blocker; do not stop at the first missing-index result.

## Contract reference

The Rust `kast` command tree is the operator surface. Use `kast --help` and
`kast <command> --help` for direct CLI commands such as `metrics`, `demo`,
`up`, and `status`. Agent and raw transport commands are hidden from top-level
help but still have scoped help, such as `kast agent --help` and
`kast rpc --help`.

For shell pipelines, use the hidden `kast agent` surface instead of hand-written
JSON-RPC plumbing. It emits one JSON envelope with `ok`, `method`, `request`,
and either `result` or `error`; `kast agent call <method>` accepts params,
full JSON-RPC requests, previous envelopes, and `nextRequest` objects through
stdin or `--params-file`. `kast rpc` remains a raw transport/debug escape hatch,
not the workflow agents should copy first.

JSON-RPC request schemas, response types, discriminated variants, and
field-level notes live in `references/commands.yaml` for reading and
`references/commands.json` for tooling. Treat that catalog as the method
contract for requests sent through `kast agent call`, not as a replacement for
the Rust CLI help.

Read `commands.yaml` when you need exact field names, types, required vs
optional, enum values, or variant discriminators. Use
`references/requests/<category>/<method>/minimal.json` and `maximal.json` for
walkable sample payloads. Validate hand-authored requests with `kast validate`
before sending them.

## Common patterns

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_PARAMS="$KAST_TMP/params.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_agent() {
  method="$1"
  params="$2"
  printf '%s\n' "$params" >"$KAST_PARAMS"
  kast agent call "$method" --params-file "$KAST_PARAMS" \
    --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
}

# Query indexed declarations with tight bounds
run_kast_agent symbol/query '{"query":"EventBean","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10}'

# Secondary module summary; request file paths only with moduleName and a small cap
run_kast_agent raw/workspace-files '{"moduleName":":analysis-api","includeFiles":false,"maxFilesPerModule":25}'

# Resolve an ambiguous symbol
kast agent resolve --symbol date --kind property \
  --containing-type com.example.EventBean --workspace-root "$PWD" >"$KAST_RESULT"

# Rank candidates before resolving
run_kast_agent symbol/discover '{"symbol":"date","fileHint":"/abs/path/EventBean.kt","line":42,"codeSnippet":"val date = event.date","maxResults":5}'

# Resolve with declaration context
kast agent resolve --symbol date --kind property \
  --containing-type com.example.EventBean --include-declaration-scope \
  --include-documentation --surrounding-lines 3 \
  --include-surrounding-members --workspace-root "$PWD" >"$KAST_RESULT"

# Find usages
kast agent references --symbol EventBean --include-declaration \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Trace callers
kast agent callers --symbol process --direction incoming --depth 3 \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Scaffold a file
kast agent scaffold --target-file /abs/path/EventBean.kt \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Rename
run_kast_agent symbol/rename '{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"}'

# Write and validate
run_kast_agent symbol/write-and-validate '{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."}'

# Diagnostics
kast agent raw-diagnostics --file-path /abs/path/File.kt \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Complex edit plans stay JSON-shaped
kast agent call raw/apply-edits --params-file "$KAST_PARAMS" \
  --workspace-root "$PWD" >"$KAST_RESULT"

# Direct source-index metrics
kast metrics impact com.example.EventBean --workspace-root "$PWD" --depth 3 \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Agent-readable symbol graph snapshot
kast demo --workspace-root "$PWD" --view symbol --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
```

## Recovery

- If a `jq` projection is wrong, inspect one item (e.g. `.references[0]`)
  before assuming field names.
- If a symbol name is broad, add `kind`, `containingType`, or `fileHint`.
- For large result sets, narrow the query before post-processing.
- Never pivot to `grep` or `rg` for Kotlin identity.
