# Kast RPC Instructions

Use `kast rpc` for direct JSON-RPC calls when a host does not expose native
Kast tools. Request shapes are catalog-backed:

- `references/commands.json` is the complete machine-readable catalog.
- `references/commands.yaml` is easier to read by hand.
- `references/requests/` contains generated request schemas and samples.

When these instruction files are installed without the full skill, inspect the
source repository or installed skill for those catalog files.

## Request Harness

Write nontrivial requests to temp files, validate them, then send them:

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/result.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

printf '%s\n' '{"jsonrpc":"2.0","method":"symbol/query","params":{"query":"EventBean","limit":10},"id":1}' >"$KAST_REQUEST"
kast validate --request-file "$KAST_REQUEST" >/dev/null
kast rpc --request-file "$KAST_REQUEST" --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
```

Every successful response is a single JSON object on stdout. Check `result`
before using the payload. When a result has an `ok` field, treat `ok=false` as a
failed operation even if the transport succeeded.

## Method Routing

- Use `symbol/*` when you have names and need Kast to resolve identity.
- Use `raw/*` when you already have exact files, offsets, or file lists.
- Use `database/*` for SQLite source-index metrics and impact views.
- Use `capabilities` before depending on optional backend operations.

Resolve identity before references, hierarchy, rename, or edits. Do not replace
symbol identity with `grep` or broad text search.
