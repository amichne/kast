# Kast quickstart

## Bootstrap once

1. Try the kast command you need.
2. If `KAST_CLI_PATH` is empty or the shell reports `command not found`, run:

   ```bash
   eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
   ```

3. Retry the same command.
4. Only then inspect the binary path or maintenance fixtures.

If the helper cannot resolve a binary, stop and report that setup blocker
instead of switching to non-semantic Kotlin search.

## Contract reference

All request schemas, response types, discriminated variants, and field-level
notes live in `references/commands.json`. That file is generated from the
Kotlin serialization models — it is always in lockstep with the CLI binary.

Read `commands.json` when you need exact field names, types, required vs
optional, enum values, or variant discriminators. Do not hard-code contract
details from this file — defer to the spec.

## Common patterns

```bash
# List workspace modules
"$KAST_CLI_PATH" workspace-files '{"includeFiles":true}'

# Resolve an ambiguous symbol
"$KAST_CLI_PATH" resolve '{"symbol":"date","kind":"property","containingType":"com.example.EventBean"}'

# Find usages
"$KAST_CLI_PATH" references '{"symbol":"EventBean","includeDeclaration":true}'

# Trace callers
"$KAST_CLI_PATH" callers '{"symbol":"process","direction":"incoming","depth":3}'

# Scaffold a file
"$KAST_CLI_PATH" scaffold '{"targetFile":"/abs/path/EventBean.kt"}'

# Rename
"$KAST_CLI_PATH" rename '{"type":"RENAME_BY_SYMBOL_REQUEST","symbol":"OldName","newName":"NewName"}'

# Write and validate
"$KAST_CLI_PATH" write-and-validate '{"type":"REPLACE_RANGE_REQUEST","filePath":"/abs/path/File.kt","startOffset":120,"endOffset":240,"content":"..."}'

# Diagnostics
"$KAST_CLI_PATH" diagnostics '{"filePaths":["/abs/path/File.kt"]}'
```

## Recovery

- If a `jq` projection is wrong, inspect one item (e.g. `.references[0]`)
  before assuming field names.
- If a symbol name is broad, add `kind`, `containingType`, or `fileHint`.
- For large result sets, narrow the query before post-processing.
- Never pivot to `grep` or `rg` for Kotlin identity.
