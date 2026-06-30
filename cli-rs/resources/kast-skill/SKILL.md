---
name: kast
description: >
  Use when working on Kotlin or Gradle semantics in a repository: `.kt` and
  `.kts` source reads or edits, symbol identity, references, callers,
  hierarchy, diagnostics, source-index metrics, semantic mutation, package
  readiness, or focused Gradle validation. Route all Kast work through
  `kast agent`.
---

# Kast Agent

Kotlin work uses the agent route: `kast agent ...` is the only first-class Kast
path. Do not use raw transport, generated protocol routes, LSP internals, or
source-only helper scripts as the agent workflow. If the active binary lacks
`kast agent`, report stale Kast installation and require upgrade or reinstall;
do not replace the missing compiler-backed path with text search.

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

Use `kast agent workflow ...` for repeated sequences. Use
`kast agent call <method>` for catalog methods that do not have a workflow fit.

| Need | Use |
| --- | --- |
| Kotlin file content with declaration context | `kast agent call symbol/scaffold` |
| File structure only | `kast agent call raw/file-outline` |
| Unknown symbol | `kast agent call symbol/discover`, then `kast agent call symbol/resolve` |
| Exact symbol identity | `kast agent call symbol/resolve` |
| Usages and incoming calls | `kast agent call symbol/references`, `kast agent call symbol/callers` |
| Offset-owned relationships | `kast agent call raw/resolve`, `kast agent call raw/references`, `kast agent call raw/call-hierarchy`, `kast agent call raw/type-hierarchy`, `kast agent call raw/implementations` |
| Workspace files, symbols, or text | `kast agent call raw/workspace-files`, `kast agent call raw/workspace-symbol`, `kast agent call raw/workspace-search` |
| Diagnostics | `kast agent call raw/diagnostics` |
| Source-index impact | `kast agent call database/metrics` or `kast agent workflow impact` |
| Rename or write | `kast agent call raw/rename`, `kast agent workflow rename-plan`, or `kast agent workflow write-validate` |
| Imports, completions, code actions, insertion points | `kast agent call raw/optimize-imports`, `kast agent call raw/completions`, `kast agent call raw/code-actions`, `kast agent call raw/semantic-insertion-point` |
| Repeated semantic sequence | `kast agent workflow symbol`, `kast agent workflow diagnostics`, `kast agent workflow rename-plan`, or `kast agent workflow write-validate` |

Use `kast agent call raw/workspace-search` only for Kotlin comments, string
literals, or other text that is not a symbol. Use ordinary file tools for exact
non-Kotlin paths, generated text, docs, skill maintenance, and final absence
checks after `kast agent` finds no candidates.

## Disclosure

Normal installed use loads only `SKILL.md`. Discover method schemas, field
names, default arguments, mutation metadata, and invocation argv through
`kast agent tools`; use `kast agent workflow --help` for supported multi-step
operations. Do not pre-load the full source catalog, generated request samples,
or raw transport runbook before a concrete command needs exact fields.

## Catalog Calls

For one nontrivial catalog call, keep parameters in a file:

```console
kast --output json agent call <method> --params-file "$KAST_PARAMS" --workspace-root "$PWD"
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
| Agent readiness or safe repair | `kast --output json ready --for agent --fix` |
| Kotlin backend readiness | `kast --output json ready --for kotlin --fix` |
| Resource setup plus runtime warmup | `kast --output json setup --workspace-root "$PWD" --no-open-ide` |
| Backend health/capabilities sweep | `kast --output json agent workflow verify --workspace-root "$PWD"` |
| Backend health check | `kast --output json agent call health --params '{}' --workspace-root "$PWD"` |
| Detailed runtime state | `kast --output json status --workspace-root "$PWD"` |
| Advertised backend capabilities | `kast --output json agent call capabilities --params '{}' --workspace-root "$PWD"` |
| Repo-local package/resource state | `kast --output json agent workflow package-verify --workspace-root "$PWD" --require-skill` |

If `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a
missing source-index database appears, run `kast --output json setup` or
`kast --output json agent workflow verify`, then retry the original Kotlin
route.
