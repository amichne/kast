---
name: kast
description: >
  Semantic Kotlin/JVM navigation and validated refactoring through direct
  `kast` commands. Use this whenever a task involves Kotlin symbol identity,
  ambiguous declarations, feature tracing, references, callers, failing Kotlin
  tests, renames, diagnostics, or safe edits, even when the user only asks to
  understand or fix a Kotlin class. Never use grep, rg, sed, or manual parsing
  for Kotlin identity.
metadata:
  short-description: Semantic Kotlin navigation and validated edits
---

# Kast

Use Kast when Kotlin work depends on declaration identity, usage scope, call
flow, or validated edits. The skill should move quickly to semantic evidence
without loading maintenance fixtures into normal code work.

## Start here

For routine navigation, run the smallest useful semantic command first. Read
`references/quickstart.md` only when you need request examples, JSON shape, or
recovery guidance. Do not load `.kast-version`, `fixtures/maintenance`,
`value-proof`, or `references/wrapper-openapi.yaml` during normal navigation.

For skill maintenance, use `value-proof/README.md`. Its benchmark loop is the
source of truth for proving whether a skill iteration beats its baseline.

## Workflow

1. Choose the narrowest operation:
   - `workspace-files` for module/file scope;
   - `scaffold` for file or type structure;
   - `resolve` for an exact declaration;
   - `references` for usages;
   - `callers` for flow or impact;
   - `rename`, `write-and-validate`, and `diagnostics` for mutations.
2. If `KAST_CLI_PATH` is empty or `kast` is missing, run
   `eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"`, retry
   the same command once, then report a setup blocker if it still fails.
3. Resolve ambiguous names before tracing. Use `kind`, `containingType`, and
   `fileHint` before `references`, `callers`, `rename`, or impact analysis.
4. Treat `ok=false`, `*_FAILURE`, dirty diagnostics, or validation/hash
   failures as failed operations. Keep the response visible and use
   diagnostics when useful; do not claim success after a failed mutation.

## Source of truth

- `references/quickstart.md`: command snippets, request shape, response shape,
  and recovery examples.
- `references/wrapper-openapi.yaml`: generated wrapper contract for contract
  changes only.
- `scripts/kast-session-start.sh`: session bootstrap.
- `value-proof/catalog.json` and `value-proof/README.md`: behavior evals and
  benchmark workflow.
- `fixtures/maintenance`: routing corpora and older eval support; keep out of
  routine navigation context.

## Command rules

- Requests and responses use camelCase.
- Check `ok` and `type` before projecting results.
- Path fields such as `filePath`, `filePaths`, `targetFile`, and
  `contentFile` must be absolute.
- `scaffold` takes one `targetFile`; run one call per file.
- `rename` and `write-and-validate` require a `type` discriminator such as
  `RENAME_BY_SYMBOL_REQUEST` or `REPLACE_RANGE_REQUEST`.
- If a projection fails, inspect one sample object and fix the projection.
- If a result set is too large, narrow the semantic query instead of switching
  to text search.
- Use `grep` or `rg` only for file-path discovery, comments, string literals,
  generated text, or skill maintenance.

## Benchmark rule

When rewriting this skill, snapshot the baseline, run the value-proof or native
skill benchmark, aggregate `benchmark.json`, generate the `eval-viewer` review
artifact, and report score, pass-rate, token, and timing deltas. Record
progression only after real benchmark evidence exists.

## Output expectations

Report the Kast command family used, the declaration/file/module scope that
anchored the answer, validation evidence before claiming success, and any
failed response or unverified assumption. For skill rewrites, also report the
benchmark path and deltas.
