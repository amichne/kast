# Kast quickstart

## Put `kast` on PATH

The ideal setup is boring: `command -v kast` succeeds and agents run `kast`
directly. Prefer a durable PATH install such as Homebrew or the managed
`~/.kast/bin/kast` launcher over per-command absolute paths.

```bash
command -v kast
kast --help
```

If `kast` is missing in an installed skill session, use the bootstrap helper as
a temporary recovery path:

1. Try the kast command you need.
2. If the shell reports `kast: command not found`, run:

   ```bash
   eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
   ```

3. Retry the same command.
4. After the session, fix PATH so future turns can call `kast` directly.

If the helper cannot resolve a binary, stop and report that setup blocker
instead of switching to non-semantic Kotlin search.

## Contract reference

The Rust `kast` command tree is the operator surface. Use `kast --help` and
`kast <command> --help` for direct CLI commands such as `metrics`, `demo`,
`up`, `status`, and `rpc`.

JSON-RPC request schemas, response types, discriminated variants, and
field-level notes live in `references/commands.json`. Treat that file as the
method catalog for requests sent through `kast rpc`, not as a replacement for
the Rust CLI help.

Read `commands.json` when you need exact field names, types, required vs
optional, enum values, or variant discriminators. Do not hard-code contract
details from this file — defer to the spec.

## Common patterns

```bash
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

# List workspace modules
kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-files","params":{"includeFiles":true},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Resolve an ambiguous symbol
kast rpc '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"date","kind":"property","containingType":"com.example.EventBean"},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Find usages
kast rpc '{"jsonrpc":"2.0","method":"symbol/references","params":{"symbol":"EventBean","includeDeclaration":true},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Trace callers
kast rpc '{"jsonrpc":"2.0","method":"symbol/callers","params":{"symbol":"process","direction":"incoming","depth":3},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Scaffold a file
kast rpc '{"jsonrpc":"2.0","method":"symbol/scaffold","params":{"targetFile":"/abs/path/EventBean.kt"},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Rename
kast rpc '{"jsonrpc":"2.0","method":"symbol/rename","params":{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Write and validate
kast rpc '{"jsonrpc":"2.0","method":"symbol/write-and-validate","params":{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Diagnostics
kast rpc '{"jsonrpc":"2.0","method":"raw/diagnostics","params":{"filePaths":["/abs/path/File.kt"]},"id":1}' \
  --workspace-root "$PWD" \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Direct source-index metrics
kast metrics impact com.example.EventBean --workspace-root "$PWD" --depth 3 \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

# Agent-readable symbol graph snapshot
kast demo --workspace-root "$PWD" --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
```

## Recovery

- If a `jq` projection is wrong, inspect one item (e.g. `.references[0]`)
  before assuming field names.
- If a symbol name is broad, add `kind`, `containingType`, or `fileHint`.
- For large result sets, narrow the query before post-processing.
- Never pivot to `grep` or `rg` for Kotlin identity.
