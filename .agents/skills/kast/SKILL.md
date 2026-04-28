---
name: kast
description: >
  Semantic Kotlin/JVM navigation and safe refactoring via `kast skill`
  subcommands. Use this whenever a task involves Kotlin/JVM symbol identity,
  class or service understanding, feature tracing, usages, ambiguous members,
  failing Kotlin tests, renames, or validated Kotlin edits, even if the user
  does not explicitly say "Kast" and only asks to understand or fix a Kotlin
  class. Never use grep/rg for Kotlin identity.
---

# Kast

Use Kast for Kotlin identity, cross-file navigation, and validated edits. The
goal is to keep semantic work moving even when the first command, JSON
projection, or edit attempt is imperfect.

## Fast path

1. Try the smallest semantic operation that can answer the request:
   `workspace-files` to find scope, `scaffold` to understand a file/type,
   `resolve` to pin a declaration, `references` for usages, and `callers` for
   flow.
2. If `KAST_CLI_PATH` is empty or the shell says `command not found`, run
   `eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"`, retry the
   same command once, and then continue. Do not start by reading
   `.kast-version`, the wrapper OpenAPI fixture, or maintenance evals.
3. Navigate only with `kast skill workspace-files`, `kast skill scaffold`,
   `kast skill resolve`, `kast skill references`, and `kast skill callers`.
4. Mutate only with `kast skill rename`,
   `kast skill write-and-validate`, and `kast skill diagnostics`.
5. For ambiguous names or member properties, resolve first with `kind`,
   `containingType`, or `fileHint`, then trace usages/callers.

## JSON shape rules

- Request JSON uses camelCase.
- Wrapper responses also use camelCase. Examples include `logFile`,
  `errorText`, `filePath`, `appliedEdits`, and `importChanges`.
- Nested API models keep the same camelCase field names. For example, symbols
  use `fqName` and locations use `location.filePath`, `startOffset`, and
  `startLine`.
- Check `ok` and `type` before projecting a response. Failure responses carry
  `stage`, `message`, optional `error` or `errorText`, and `logFile`.
- `rename` and `write-and-validate` requests require a `type` discriminator
  such as `RENAME_BY_SYMBOL_REQUEST` or `REPLACE_RANGE_REQUEST`.
- Any request field ending in `filePath`, `filePaths`, or `contentFile` should
  use an absolute path.
- `scaffold` uses `targetFile` (singular absolute path) as the required field,
  not `filePaths`. `workspaceRoot` defaults to the current working directory
  when omitted. Run one `scaffold` call per file; there is no batch variant.

## Recovery rules

- If parsing a result fails, inspect the top-level object or one sample element,
  then adjust the projection. Do not switch to text search because of JSON
  friction.
- If a result set is too large, narrow the same semantic query with `kind`,
  `containingType`, `fileHint`, depth, or result limits.
- If a mutation returns `ok=false`, a `*_FAILURE` response type, dirty
  diagnostics, or a validation/hash message such as "Missing expected hash",
  treat the edit as failed. Keep the failure visible, run diagnostics on the
  intended/touched file when useful, and report the blocker instead of claiming
  success or applying a hand edit.
- Never replace a failed semantic query with `grep`, `rg`, `sed`, or manual
  parsing for Kotlin identity. Raw search is only acceptable for non-semantic
  file-path discovery, comments, string literals, or maintenance work.
- If a request fails with `Encountered an unknown key`, a field name is wrong.
  Consult `references/quickstart.md` for the correct shape. Skill subcommands
  do not accept `--help`; probing with `{}` to discover required fields wastes
  turns when the quickstart already lists every command's required fields.

Read `references/quickstart.md` for request snippets and recovery tips. Skill
maintenance fixtures live under `fixtures/maintenance`; do not load them during
normal Kotlin navigation.
