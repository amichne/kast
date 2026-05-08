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
flow, or validated edits.

## Version contract

The generated spec at `references/commands.json` is the single source of truth
for every command's request schema, response types, discriminated variants, and
notes. Read it once per session when you need field-level detail. It is
regenerated from the Kotlin serialization models at build time — if a field
exists in the spec it exists in the CLI, and vice versa.

## Start here

Run the smallest useful semantic command first. Load `references/commands.json`
only when you need request field names, types, or discriminator values.

Do not load `.kast-version`, `fixtures/maintenance`, `value-proof`, or
`references/wrapper-openapi.yaml` during normal navigation.

For skill maintenance, use `value-proof/README.md`.

## Routing

Pick the narrowest command for the task:

| When you need to… | Command |
| --- | --- |
| Discover modules or source files | `workspace-files` |
| Search Kotlin workspace text or regex | `workspace-search` |
| Find symbols by name across the workspace | `workspace-symbol` |
| Understand a file or type structure | `scaffold` |
| Get a lightweight file outline | `file-outline` |
| Find the exact declaration of a symbol | `resolve` |
| Find every usage of a symbol | `references` |
| Trace incoming/outgoing call flow | `callers` |
| Rename a symbol safely across the project | `rename` |
| Apply code and validate it compiles | `write-and-validate` |
| Re-check files after mutation | `diagnostics` |
| Query indexed source metrics | `metrics` |

Use the native wrappers when the host exposes them:

- `kast_workspace_files` or `kast workspace-files`
- `kast_workspace_search` or `kast workspace-search`
- `kast_workspace_symbol` or `kast workspace-symbol`
- `kast_scaffold` or `kast scaffold`
- `kast_file_outline` or `kast file-outline`
- `kast_resolve` or `kast resolve`
- `kast_references` or `kast references`
- `kast_callers` or `kast callers`
- `kast_diagnostics` or `kast diagnostics`
- `kast_rename` or `kast rename`
- `kast_write_and_validate` or `kast write-and-validate`
- `kast_metrics` or `kast metrics`

## Session bootstrap

If `KAST_CLI_PATH` is empty or `kast` is missing, run:

```bash
eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
```

Retry the same command once, then report a setup blocker if it still fails.

## Rules

- All request and response JSON uses camelCase.
- Check `ok` and `type` before projecting results.
- Path fields (`filePath`, `filePaths`, `targetFile`, `contentFile`) must be
  absolute.
- `rename` and `write-and-validate` require a `type` discriminator — see
  `references/commands.json` for the exact variant names per command.
- `scaffold` takes one `targetFile`; run one call per file.
- Treat `ok=false`, `*_FAILURE`, dirty diagnostics, or validation/hash failures
  as failed operations. Do not claim success after a failed mutation.
- If a projection fails, inspect one sample object and fix the projection.
- If a result set is too large, narrow the semantic query instead of switching
  to text search.
- Use `grep` or `rg` only for file-path discovery, comments, string literals,
  generated text, or skill maintenance.

## Source of truth

- `references/commands.json`: version-keyed command spec with full request
  schemas, response types, discriminated variants, and usage notes.
- `scripts/kast-session-start.sh`: session bootstrap.
- `value-proof/catalog.json` and `value-proof/README.md`: behavior evals and
  benchmark workflow.

## Benchmark rule

When rewriting this skill, snapshot the baseline, run the value-proof or native
skill benchmark, aggregate `benchmark.json`, generate the `eval-viewer` review
artifact, and report score, pass-rate, token, and timing deltas.
