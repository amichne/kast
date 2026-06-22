# Kast Agent Pipe Instructions

Use `kast agent` for CLI pipelines when a host does not expose native Kast
tools. Request shapes are catalog-backed:

- `references/commands.json` is the complete machine-readable catalog.
- `references/commands.yaml` is easier to read by hand.
- `references/requests/` contains generated request schemas and samples.

When these instruction files are installed without the full skill, inspect the
source repository or installed skill for those catalog files.

## Agent Pipe Path

`kast agent` wraps every call in a stable JSON envelope with `ok`, `method`,
`request`, and either `result` or `error`, so downstream commands can consume
the prior output without hand-rebuilding JSON-RPC requests.

Use flag aliases for shallow requests:

```sh
kast agent resolve --symbol EventBean --workspace-root "$PWD"
kast agent raw-resolve --file-path "$PWD/src/main/kotlin/App.kt" --offset 128 --workspace-root "$PWD"
```

Use `kast agent call <method>` for complex payloads or escape hatches. The
input can be a params object, a full JSON-RPC request, a previous agent
envelope, or an object with `nextRequest`:

```sh
printf '%s\n' '{"symbol":"EventBean","includeDocumentation":true}' |
  kast agent call symbol/resolve --workspace-root "$PWD"
```

Keep deeply nested payloads such as `raw/apply-edits` JSON-shaped and pass them
through `kast agent call raw/apply-edits --params-file request.json`.
`kast rpc --request-file` remains available only as a raw transport/debug
escape hatch.

## Request Harness

Write nontrivial params to temp files, then send them through `kast agent`:

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_PARAMS="$KAST_TMP/params.json"
KAST_RESULT="$KAST_TMP/result.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

printf '%s\n' '{"query":"EventBean","limit":10}' >"$KAST_PARAMS"
kast agent call symbol/query --params-file "$KAST_PARAMS" --workspace-root "$PWD" >"$KAST_RESULT" 2>"$KAST_STDERR"
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
