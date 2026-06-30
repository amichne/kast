# Kast fallback runbook

Use the `kast agent` patterns from `quickstart.md` first. This runbook is for
the rare case where a preserved JSON-RPC envelope is the task input.

```sh
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_agent_call() {
  method="$1"
  request="$2"
  printf '%s\n' "$request" >"$KAST_REQUEST"
  kast developer release validate --request-file "$KAST_REQUEST" >/dev/null
  kast agent call "$method" --request-file "$KAST_REQUEST" --workspace-root "$PWD" \
    >"$KAST_RESULT" 2>"$KAST_STDERR"
}

warm_idea_backend_if_needed() {
  # Use this when kast is installed but agent/source-index output reports
  # NO_BACKEND_AVAILABLE, INDEX_UNAVAILABLE, METRICS_DB_UNAVAILABLE, or a
  # missing source-index database.
  kast setup --workspace-root "$PWD" --backend idea --no-open-ide
}

run_kast_agent_call symbol/query '{"jsonrpc":"2.0","method":"symbol/query","params":{"query":"EventBean","modes":["exact","lexical"],"filters":{"relativePathPrefix":"src/"},"limit":10},"id":1}'
run_kast_agent_call raw/workspace-files '{"jsonrpc":"2.0","method":"raw/workspace-files","params":{"moduleName":":analysis-api","includeFiles":false,"maxFilesPerModule":25},"id":1}'
run_kast_agent_call symbol/resolve '{"jsonrpc":"2.0","method":"symbol/resolve","params":{"symbol":"date","kind":"property","containingType":"com.example.EventBean"},"id":1}'
run_kast_agent_call symbol/discover '{"jsonrpc":"2.0","method":"symbol/discover","params":{"symbol":"date","fileHint":"/abs/path/EventBean.kt","line":42,"codeSnippet":"val date = event.date","maxResults":5},"id":1}'
run_kast_agent_call symbol/references '{"jsonrpc":"2.0","method":"symbol/references","params":{"symbol":"EventBean","includeDeclaration":true},"id":1}'
run_kast_agent_call symbol/callers '{"jsonrpc":"2.0","method":"symbol/callers","params":{"symbol":"process","direction":"incoming","depth":3},"id":1}'
run_kast_agent_call symbol/scaffold '{"jsonrpc":"2.0","method":"symbol/scaffold","params":{"targetFile":"/abs/path/EventBean.kt"},"id":1}'
run_kast_agent_call symbol/rename '{"jsonrpc":"2.0","method":"symbol/rename","params":{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"},"id":1}'
run_kast_agent_call symbol/write-and-validate '{"jsonrpc":"2.0","method":"symbol/write-and-validate","params":{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."},"id":1}'
run_kast_agent_call raw/diagnostics '{"jsonrpc":"2.0","method":"raw/diagnostics","params":{"filePaths":["/abs/path/File.kt"]},"id":1}'

kast developer inspect metrics impact com.example.EventBean --workspace-root "$PWD" --depth 3 \
  >"$KAST_RESULT" 2>"$KAST_STDERR"

kast developer inspect demo --workspace-root "$PWD" --view symbol --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
```
