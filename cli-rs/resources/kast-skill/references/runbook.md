# Kast RPC runbook

This runbook shows the standard request-file harness for `kast rpc`.

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_rpc() {
  printf '%s\n' "$1" >"$KAST_REQUEST"
  kast validate --request-file "$KAST_REQUEST" >/dev/null
  kast rpc --request-file "$KAST_REQUEST" --workspace-root "$PWD" \
    >"$KAST_RESULT" 2>"$KAST_STDERR"
}

warm_idea_backend_if_needed() {
  # Use this when kast is installed but RPC/source-index output reports
  # NO_BACKEND_AVAILABLE, INDEX_UNAVAILABLE, METRICS_DB_UNAVAILABLE, or a
  # missing source-index database.
  kast up --workspace-root "$PWD" --backend idea
}

run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/query","params":{"query":"EventBean","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"raw/workspace-files","params":{"moduleName":":analysis-api","includeFiles":false,"maxFilesPerModule":25},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"date","kind":"property","containingType":"com.example.EventBean"},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/discover","params":{"symbol":"date","fileHint":"/abs/path/EventBean.kt","line":42,"codeSnippet":"val date = event.date","maxResults":5},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/references","params":{"symbol":"EventBean","includeDeclaration":true},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/callers","params":{"symbol":"process","direction":"incoming","depth":3},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/scaffold","params":{"targetFile":"/abs/path/EventBean.kt"},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/rename","params":{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"symbol/write-and-validate","params":{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."},"id":1}'
run_kast_rpc '{"jsonrpc":"2.0","method":"raw/diagnostics","params":{"filePaths":["/abs/path/File.kt"]},"id":1}'

kast metrics impact com.example.EventBean --workspace-root "$PWD" --depth 3 \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

kast demo --workspace-root "$PWD" --view symbol --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
```
