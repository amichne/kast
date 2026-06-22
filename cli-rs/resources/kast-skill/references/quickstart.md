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
`up`, `status`, and `rpc`.

JSON-RPC request schemas, response types, discriminated variants, and
field-level notes live in `references/commands.yaml` for reading and
`references/commands.json` for tooling. Treat that catalog as the method
contract for requests sent through `kast rpc`, not as a replacement for the
Rust CLI help.

Read `commands.yaml` when you need exact field names, types, required vs
optional, enum values, or variant discriminators. Use
`references/requests/<category>/<method>/minimal.json` and `maximal.json` for
walkable sample payloads. Validate hand-authored requests with `kast validate`
before sending them.

## Common patterns

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_rpc() {
  printf '%s\n' "$1" >"$KAST_REQUEST"
  kast validate --request-file "$KAST_REQUEST" >/dev/null
  kast rpc --request-file "$KAST_REQUEST" --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
}

# Query indexed declarations with tight bounds
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/query","params":{"query":"EventBean","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10},"id":1}'

# Secondary module summary; request file paths only with moduleName and a small cap
run_kast_rpc '{"jsonrpc":"2.0","method":"raw/workspace-files","params":{"moduleName":":analysis-api","includeFiles":false,"maxFilesPerModule":25},"id":1}'

# Resolve an ambiguous symbol
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"date","kind":"property","containingType":"com.example.EventBean"},"id":1}'

# Rank candidates before resolving
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/discover","params":{"symbol":"date","fileHint":"/abs/path/EventBean.kt","line":42,"codeSnippet":"val date = event.date","maxResults":5},"id":1}'

# Resolve with declaration context
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"date","kind":"property","containingType":"com.example.EventBean","includeDeclarationScope":true,"includeDocumentation":true,"surroundingLines":3,"includeSurroundingMembers":true},"id":1}'

# Find usages
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/references","params":{"symbol":"EventBean","includeDeclaration":true},"id":1}'

# Trace callers
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/callers","params":{"symbol":"process","direction":"incoming","depth":3},"id":1}'

# Scaffold a file
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/scaffold","params":{"targetFile":"/abs/path/EventBean.kt"},"id":1}'

# Rename
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/rename","params":{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"},"id":1}'

# Write and validate
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/write-and-validate","params":{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."},"id":1}'

# Diagnostics
run_kast_rpc '{"jsonrpc":"2.0","method":"raw/diagnostics","params":{"filePaths":["/abs/path/File.kt"]},"id":1}'

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
