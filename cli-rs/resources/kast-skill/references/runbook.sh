#!/bin/bash

## This script demonstrates how to use the KAST CLI to perform various code analysis and transformation tasks via its JSON-RPC interface.
# It creates a temporary directory to store request and response files,
# and defines a helper function `run_kast_rpc` to send requests and capture results.
# The script .
KAST_TMP="$(mktemp -d)"
trap 'rm -rf "$KAST_TMP"' EXIT
SKILL_DIR=".agents/skills/kast"
KAST_REQUEST="$KAST_TMP/request.json"
KAST_RESULT="$KAST_TMP/kast.json"
KAST_STDERR="$KAST_TMP/kast.stderr"

run_kast_rpc() {
  printf '%s\n' "$1" >"$KAST_REQUEST"
  python3 "$SKILL_DIR/scripts/validate-rpc-request.py" --request-file "$KAST_REQUEST" >/dev/null
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
kast demo --workspace-root "$PWD" --query EventBean --json \
  >"$KAST_RESULT" 2>"$KAST_STDERR"
