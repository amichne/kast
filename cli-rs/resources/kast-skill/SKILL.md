---
name: kast
description: >
  Kotlin semantic work in Gradle repositories. Use `kast agent` for Kotlin
  `.kt` or `.kts` source reads and edits, symbol identity, references, callers,
  hierarchy, diagnostics, source-index metrics, file-backed catalog calls, and
  focused Gradle validation.
---

# Kotlin

Kotlin work uses `kast agent`. If this skill is loaded, assume `kast agent` is
available and do the Kotlin work instead of proving the installation. Treat
`kast agent ...` as the only first-class path.

## Operating Loop

1. Route to the narrowest Kotlin-aware `kast agent` surface before reading
   files, searching text, or guessing project structure.
2. Keep using `kast agent` after the first successful call. A first result is
   not a handoff back to generic file reads; stay on `kast agent` until the work
   leaves Kotlin semantics or it reports a concrete blocker.
3. Mutate through `kast agent` when the target is semantic or compiler-owned.
   Validate with `kast agent` diagnostics and then the narrowest Gradle task
   that proves the change.

Completion criterion: every Kotlin semantic claim, edit target, relationship
set, and completion proof is backed by `kast agent` evidence, or the remaining
work is an exact non-Kotlin path that does not depend on semantic facts.

## Usage Routes

Use direct `kast agent` subcommands first. Use `kast agent workflow ...` for
repeated sequences. Use `kast agent call <method>` only when no direct subcommand
or workflow fits.

| Need | Use |
| --- | --- |
| Kotlin file content with declaration context | `kast agent scaffold` |
| File structure only | `kast agent file-outline` |
| Unknown symbol | `kast agent discover`, then `kast agent resolve` |
| Exact symbol identity | `kast agent resolve` |
| Usages and incoming calls | `kast agent references`, `kast agent callers` |
| Offset-owned relationships | `kast agent raw-resolve`, `kast agent raw-references`, `kast agent raw-call-hierarchy`, `kast agent raw-type-hierarchy`, `kast agent raw-implementations` |
| Workspace files, symbols, or text | `kast agent workspace-files`, `kast agent workspace-symbol`, `kast agent workspace-search` |
| Diagnostics | `kast agent raw-diagnostics` |
| Source-index impact | `kast agent metrics` or `kast agent workflow impact` |
| Rename or write | `kast agent raw-rename`, `kast agent workflow rename-plan`, or `kast agent workflow write-validate` |
| Imports, completions, code actions, insertion points | `kast agent raw-optimize-imports`, `kast agent raw-completions`, `kast agent raw-code-actions`, `kast agent raw-semantic-insertion-point` |
| Repeated semantic sequence | `kast agent workflow symbol`, `kast agent workflow diagnostics`, `kast agent workflow rename-plan`, or `kast agent workflow write-validate` |

Use `kast agent workspace-search` only for Kotlin comments, string literals, or
other text that is not a symbol. Use ordinary file tools for exact non-Kotlin
paths, generated text, docs, skill maintenance, and final absence checks after
`kast agent` finds no candidates.

## Catalog Calls

Use `kast agent tools` to discover the live method list, schemas, default
arguments, mutation metadata, and invocation shape. For one nontrivial catalog
call, keep parameters in a file:

```console
kast agent --output json call <method> --params-file "$KAST_PARAMS" --workspace-root "$PWD"
```

Use camelCase fields and absolute paths. A call succeeds only when the outer
`ok` field and the nested result status are clean. Validation errors,
`ok=false`, dirty diagnostics, hash mismatches, and failed Gradle tasks fail the
operation.

## Health Reference

Use this section only when a `kast agent` command fails, the user asks for
readiness evidence, or backend state is part of the task. Do not make these
commands the first move for normal Kotlin work.

| Symptom or need | Command |
| --- | --- |
| Agent readiness or safe repair | `kast agent --output json ready --for agent --fix` |
| Kotlin backend readiness | `kast agent --output json ready --for kotlin --fix` |
| Resource setup plus runtime warmup | `kast agent --output json up --workspace-root "$PWD" --no-onboard` |
| Backend health/capabilities sweep | `kast agent --output json workflow verify --workspace-root "$PWD"` |
| Direct health RPC | `kast agent --output json health --workspace-root "$PWD"` |
| Detailed runtime state | `kast agent --output json runtime-status --workspace-root "$PWD"` |
| Advertised backend capabilities | `kast agent --output json capabilities --workspace-root "$PWD"` |
| Repo-local package/resource state | `kast agent --output json workflow package-verify --workspace-root "$PWD" --require-skill` |

If `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a
missing source-index database appears, run `kast agent --output json up` or
`kast agent --output json workflow verify`, then retry the original Kotlin
route.
