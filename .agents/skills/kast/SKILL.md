---
name: kast
description: >
  Use whenever a task touches Kotlin/JVM symbol identity, class or service
  understanding, feature tracing, usages, ambiguous members, source metrics,
  failing Kotlin tests, renames, diagnostics, or validated Kotlin edits — even
  if the user only says "understand this Kotlin class" or "fix this test".
  Kast is the heart of all Kotlin code insight in this repository; reach for
  it before `view`, `grep`, or generic file edits on `.kt`/`.kts` source.
---

# Kast

Kast turns Kotlin code into structured, semantic answers at a fraction of the
token cost of reading source. The Copilot CLI extension at
`.github/extensions/kast/extension.mjs` registers native tools that map 1:1 to
the underlying `kast skill` commands, resolves the binary once at session
start, and warns when generic `view`/`grep`/`edit`/`create` reaches for
`.kt`/`.kts` source.

## Native tools (preferred path)

Each tool runs the corresponding `kast skill` command, returns its JSON, and
validates arguments against a schema — no shell escaping, no JSON-in-bash.

| Need                                          | Tool                       |
| --------------------------------------------- | -------------------------- |
| List modules / source files                   | `kast_workspace_files`     |
| Understand a file or type (semantic skeleton) | `kast_scaffold`            |
| Resolve an exact declaration                  | `kast_resolve`             |
| Find every usage of a symbol                  | `kast_references`          |
| Trace incoming/outgoing call hierarchy        | `kast_callers`             |
| Indexed metrics (fan-in/out, dead-code, …)    | `kast_metrics`             |
| Rename safely (updates every reference)       | `kast_rename`              |
| Apply an edit and validate it atomically      | `kast_write_and_validate`  |
| Re-check files after a mutation               | `kast_diagnostics`         |

The extension caches the resolved binary path the first time a tool runs and
reuses it for the rest of the session. There is no bootstrap step to perform
manually.

## When to reach for which tool

1. **Discover scope.** `kast_workspace_files` (omit `includeFiles` for the
   module map; set `includeFiles:true` only when you need a per-module file
   list, and raise `maxFilesPerModule` only when truncation matters).
2. **Read a Kotlin file.** Always start with `kast_scaffold` — declarations,
   signatures, imports, and key call sites in a fraction of the tokens of
   `view`. Read the raw `.kt` only for non-semantic concerns (comments,
   formatting, generated headers).
3. **Pin an ambiguous name.** `kast_resolve` with `kind`, `containingType`,
   or `fileHint` to disambiguate overloads, inherited members, and
   shadowed names before tracing references or callers.
4. **Find usages.** `kast_references` — never `grep` for Kotlin identity.
   Grep cannot tell an overload from a sibling, an alias from an import,
   or a property from a getter.
5. **Trace flow.** `kast_callers` (`direction:incoming` for blast-radius
   questions; `outgoing` for "what does this call?"). Bound depth and
   `maxTotalCalls` to keep results scannable.
6. **Mutate.** `kast_rename` for symbol renames; `kast_write_and_validate`
   for content edits. Both run validation atomically; treat any non-clean
   response as a failed change. Do not fall back to the generic `edit`/
   `create` tool on `.kt`/`.kts` source — it bypasses the validator.
7. **Validate.** `kast_diagnostics` after any mutation that did not already
   validate the touched files.

## JSON shape rules

These come straight from the wire contract; the tool schemas enforce most of
them, but a few patterns repeat in every response.

- Every request and response uses **camelCase** field names — wrapper
  metadata (`logFile`, `errorText`, `filePath`, `appliedEdits`) and nested
  API models (`symbol.fqName`, `location.filePath`, `startOffset`).
- Always check `ok` and `type` before projecting. Failure responses carry
  `stage`, `message`, optional `error` or `errorText`, and `logFile`.
- File path fields (`filePath`, `filePaths`, `contentFile`) require absolute
  paths.
- `kast_rename` and `kast_write_and_validate` need a `type` discriminator
  (`RENAME_BY_SYMBOL_REQUEST`, `RENAME_BY_OFFSET_REQUEST`,
  `CREATE_FILE_REQUEST`, `INSERT_AT_OFFSET_REQUEST`, or
  `REPLACE_RANGE_REQUEST`). Required fields per discriminator are listed in
  `references/quickstart.md`.
- `kast_scaffold` uses `targetFile` (singular absolute path), not
  `filePaths`. There is no batch variant; one call per file.
- `workspaceRoot` defaults to the current working directory when omitted.

## Recovery

- **Wrong projection.** Inspect one element of the response (e.g.
  `references[0]`) before assuming a field name is missing. Adjust the
  projection — never switch to text search because of JSON friction.
- **Result set too large.** Narrow the same semantic query with `kind`,
  `containingType`, `fileHint`, lower `depth`, or smaller limits. Don't
  post-filter unindexed text.
- **Truncated workspace files.** If `kast_workspace_files` marks a module
  with `filesTruncated:true`, raise the cap only when the task genuinely
  needs the wider list. Prefer a tighter semantic query.
- **Stale or missing index.** If `kast_metrics` reports the reference
  index is missing or stale, treat results as advisory and rebuild the
  index before relying on impact or dead-code answers.
- **Failed mutation.** `ok:false`, a `*_FAILURE` response type, dirty
  diagnostics, or a hash/validation message such as
  `Missing expected hash` means the edit did not commit. Keep the failure
  visible, run `kast_diagnostics` if it clarifies the state, and report
  the blocker — do not silently retry with a hand edit.
- **Unknown field error.** A request key is wrong. Consult
  `references/quickstart.md` for the correct shape; do not probe with
  `{}`.
- **Never** replace a failed semantic query with `grep`, `rg`, `sed`, or
  manual parsing for Kotlin identity. Raw search is acceptable only for
  non-semantic work: file-path discovery in non-Kotlin files, comments,
  string literals, and maintenance scripts.

## Bash fallback

If you ever need to run `kast skill` directly (debugging the extension,
custom shell pipelines), the `KAST_CLI_PATH` env var is announced in the
session-start context. Pass it inline per command:

```bash
KAST_CLI_PATH=/abs/path/kast "$KAST_CLI_PATH" skill workspace-files '{}'
```

`export` does not persist across bash tool calls in this environment, so set
the variable on the same line.

## Maintenance

- `references/quickstart.md` — request snippets and per-command field shapes.
- `fixtures/maintenance/` — eval corpora for skill maintenance work; do not
  load during normal Kotlin navigation.
