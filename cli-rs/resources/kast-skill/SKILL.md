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
available and do the Kotlin work instead of proving the installation, unless
the task is about readiness or package state. Treat `kast agent` as the only
first-class path.

## Loop

1. Use `kast agent workflow ...` when a workflow fits, or `kast agent call <method>` for the narrowest single catalog method.
2. Keep nontrivial params in a JSON file and pass `--params-file`; use `kast agent tools` only when exact fields, variants, or mutation metadata are needed.
3. Keep response output JSON by default; use `--format toon` only for large read-only outputs when the host can consume TOON.
4. Stay on `kast agent` after the first successful call. Switch to generic file reads or text search only when the work leaves Kotlin semantics or Kast reports a concrete blocker.
5. Mutate through `kast agent` for semantic or compiler-owned targets, then validate with Kast diagnostics/workflows and the narrowest Gradle task.

Completion criterion: every Kotlin semantic claim, edit target, relationship set, and completion proof is backed by `kast agent` evidence, or the remaining work is an exact non-Kotlin path.

## Usage Routes

- Unknown symbol or broad Kotlin discovery: start with `kast agent call symbol/query`, then use `symbol/discover` or `symbol/resolve` when context is needed.
- File context: use `kast agent call symbol/scaffold`; use `kast agent call raw/file-outline` only for structure without full file contents.
- Relationships and impact: use `symbol/references`, `symbol/callers`, `database/metrics`, or `kast agent workflow impact`.
- Repeatable proof or mutation: use `kast agent workflow symbol`, `diagnostics`, `rename-plan`, `write-validate`, or `package-verify`.
- Use ordinary file tools for exact non-Kotlin paths, generated text, docs, skill maintenance, and final absence checks after `kast agent` finds no candidates.
- Use raw catalog methods only after a symbol-first route or workflow does not fit, or when you already have exact files and offsets for a bounded operation.

For one nontrivial catalog call:

```console
kast agent --output json call <method> --params-file "$KAST_PARAMS" --workspace-root "$PWD"
```

Use camelCase fields and absolute paths. A call succeeds only when the outer `ok` field and nested result status are clean; validation errors, dirty diagnostics, hash mismatches, and failed Gradle tasks fail the operation.

## Health Reference

Use this section only when a `kast agent` command fails, the user asks for readiness evidence, or backend state is part of the task. Do not make these commands the first move for normal Kotlin work.

If `NO_BACKEND_AVAILABLE`, `INDEX_UNAVAILABLE`, `METRICS_DB_UNAVAILABLE`, or a missing source-index database appears, run `kast agent --output json workflow verify --workspace-root "$PWD"` or `kast agent --output json up --workspace-root "$PWD" --no-onboard`, then retry the original Kotlin route.
For repo-local package/resource state, run `kast agent --output json workflow package-verify --workspace-root "$PWD"` with the `--require-*` flags that match the task, then follow emitted recovery commands.

Do not teach `kast rpc`, generated protocol paths, LSP capability internals, or backend implementation classes as public agent APIs.
