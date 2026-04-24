# Kast quickstart

## Bootstrap once

1. Try the real Kast command you need.
2. If `KAST_CLI_PATH` is empty or the shell reports `command not found`, run:

   ```bash
   eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
   ```

3. Retry the same command.
4. Only then inspect the binary path or maintenance fixtures.

Do not start by reading `.kast-version` or the full OpenAPI fixture.

## Command map

| Task | Command |
| --- | --- |
| List modules or source files | `kast skill workspace-files` |
| Understand a file or type | `kast skill scaffold` |
| Resolve an exact declaration | `kast skill resolve` |
| Find usages or constructor call sites | `kast skill references` |
| Trace incoming/outgoing flow | `kast skill callers` |
| Rename safely | `kast skill rename` |
| Apply code and validate it | `kast skill write-and-validate` |
| Re-check touched files | `kast skill diagnostics` |

## Request and response shape

- Request JSON uses camelCase.
- Response JSON uses snake_case.
- Any field ending in `filePath`, `filePaths`, or `contentFile` should be an
  absolute path.

## Common requests

### List workspace files

```bash
"$KAST_CLI_PATH" skill workspace-files '{"includeFiles":true}'
```

### Resolve an ambiguous property

```bash
"$KAST_CLI_PATH" skill resolve '{
  "symbol":"date",
  "kind":"property",
  "containingType":"com.example.EventBean",
  "fileHint":"/abs/path/EventBean.kt"
}'
```

### Find usages of a type or property

```bash
"$KAST_CLI_PATH" skill references '{
  "symbol":"EventBean",
  "fileHint":"/abs/path/EventBean.kt",
  "includeDeclaration":true
}'
```

### Trace callers

```bash
"$KAST_CLI_PATH" skill callers '{
  "symbol":"process",
  "direction":"incoming",
  "depth":3,
  "maxTotalCalls":256,
  "maxChildrenPerNode":64
}'
```

### Understand a file quickly

```bash
"$KAST_CLI_PATH" skill scaffold '{
  "targetFile":"/abs/path/EventBean.kt",
  "targetSymbol":"EventBean",
  "mode":"implement"
}'
```

### Rename

```bash
"$KAST_CLI_PATH" skill rename '{
  "type":"RENAME_BY_SYMBOL_REQUEST",
  "symbol":"OldName",
  "newName":"NewName"
}'
```

### Write and validate

```bash
"$KAST_CLI_PATH" skill write-and-validate '{
  "type":"REPLACE_RANGE_REQUEST",
  "filePath":"/abs/path/File.kt",
  "startOffset":120,
  "endOffset":240,
  "content":"..."
}'
```

### Validate touched files

```bash
"$KAST_CLI_PATH" skill diagnostics '{
  "filePaths":["/abs/path/File.kt"]
}'
```

## Recovery

- If a `jq` projection is wrong, inspect one item first, for example
  `.references[0]`, before assuming field names.
- If a symbol name is broad, add `kind`, `containingType`, or `fileHint`.
- For large result sets, narrow the query before post-processing.
- Never pivot to `grep` or `rg` for Kotlin identity.
