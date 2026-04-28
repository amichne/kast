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
If the helper cannot resolve a binary, stop and report that setup blocker
instead of switching to non-semantic Kotlin search.

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
- Wrapper responses also use camelCase.
- Nested API model fields use the same camelCase names. Common examples are
  `symbol.fqName`, `symbol.location.filePath`, `symbol.location.startOffset`,
  and `references[].location.filePath`.
- Any field ending in `filePath`, `filePaths`, or `contentFile` should be an
  absolute path.
- Check `ok` and `type` first. Failure responses include `stage`, `message`,
  optional `error` or `errorText`, and `logFile`.
- `rename` and `write-and-validate` requests require a `type` discriminator:
  `RENAME_BY_SYMBOL_REQUEST`, `RENAME_BY_OFFSET_REQUEST`,
  `CREATE_FILE_REQUEST`, `INSERT_AT_OFFSET_REQUEST`, or
  `REPLACE_RANGE_REQUEST`.
- `workspaceRoot` defaults to the current working directory when omitted.

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

If a projection such as `.references[].location.filePath` returns nothing, inspect one
item before assuming the command failed:

```bash
"$KAST_CLI_PATH" skill references '{"symbol":"EventBean","includeDeclaration":true}' \
  | jq '{ok,type,firstReference:.references[0]}'
```

Wrapper metadata and nested API fields both use camelCase, including
`logFile`, `filePath`, and `location.filePath`.

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
  "workspaceRoot":"/abs/path/project",
  "mode":"implement"
}'
```

`targetFile` is singular (not `filePaths`). `workspaceRoot` defaults to the
current working directory when omitted. Run one `scaffold` call per file; there
is no batch variant.

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

If this returns `ok:false`, `WRITE_AND_VALIDATE_FAILURE`, dirty diagnostics, or
a validation/hash error such as `Missing expected hash for edited file`, the
edit did not succeed. Keep the failed response in view, run `diagnostics` on the
intended file if that helps explain the state, and report the blocker rather
than applying a manual edit.

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
