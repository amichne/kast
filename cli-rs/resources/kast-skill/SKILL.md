---
name: kast
description: >
  Kotlin semantic work in Gradle repositories. Use when an agent needs compiler-backed
  Kotlin `.kt` or `.kts` discovery, symbol identity, references, callers, diagnostics,
  source-index impact, semantic rename, typed scope mutation, or focused Gradle
  validation.
---

# Kast

Use `kast agent` before generic file reads, text search, or hand-written edits for
Kotlin and Gradle semantic work. Treat `kast`, `kast help`, and this skill as the
public dialect; do not use catalog, workflow, hook, or Copilot package helpers as
the first iteration surface.

## Loop

1. Orient with `kast`, `kast help agent`, and read-only `kast ready --workspace-root "$PWD"` when install or backend state matters.
2. Discover owned Kotlin paths with `kast agent workspace-files --workspace-root "$PWD"`; narrow with typed filters and continue only with its opaque page token. Then pass its `filePath` directly to diagnostics or exact symbol `--file-hint`.
3. Resolve identity with `kast agent symbol --query <name> --workspace-root "$PWD"`; exact lookup is the default. Use `--mode discovery` only for fuzzy candidates, then rerun exact lookup. Preserve the returned `fqName`, `declarationFile`, `declarationStartOffset`, and optional `kind`/`containingType` together as one selector.
4. Pass that complete selector to `kast agent references`, `callers`, `callees`, `implementations`, or `hierarchy`. Compact mode returns four typed records by default; repeat the same selector and options with `--page-token <nextPageToken>` to continue without rediscovery.
5. Check changed files with `kast agent diagnostics --file-path src/main/kotlin/App.kt --workspace-root "$PWD"`. Compact mode returns at most eight actionable records with exact full-set counts; continue with `--page-token <nextPageToken>` when needed.
6. Pass the same selector to `kast agent impact`; compact mode bounds the database page to four nodes, `--page-token` continues the ordered source-index result, and functions or properties may return `IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` rather than attributing aggregate rows to one overload.
7. Mutate only through typed plans. First run `kast agent rename`, `add-file`, `add-declaration`, `add-implementation`, `add-statement`, or `replace-declaration` without `--apply`; then add `--apply --idempotency-key <stable-key>` after reviewing the plan and content file.
8. Agent results are compact by default. Treat `EXACT` and `KNOWN_MINIMUM` relationship cardinality distinctly. Use `--output json` for JSON-only parsed scripts, `--fields <family-fields>` for a typed subset, `--count` for aggregates, and `--verbose` or `--explain` only when the task needs detailed evidence.

Completion criterion: every Kotlin semantic claim, edit target, relationship set,
and validation result is backed by a typed `kast agent` command, or the remaining
work is explicitly outside Kotlin semantics.

## Mutation Commands

- `kast agent rename --symbol <fq-name> --new-name <name> --workspace-root "$PWD"` plans an identity-first rename.
- `kast agent add-file --file-path src/main/kotlin/NewType.kt --content-file <snippet.kt> --workspace-root "$PWD"` plans a file creation.
- `kast agent add-declaration --inside-file src/main/kotlin/App.kt --at file-bottom --content-file <snippet.kt> --workspace-root "$PWD"` plans declaration insertion.
- `kast agent add-implementation --inside-scope <fq-name> --at body-end --content-file <snippet.kt> --workspace-root "$PWD"` plans implementation insertion.
- `kast agent add-statement --inside-scope <fq-name> --at body-end --content-file <snippet.kt> --workspace-root "$PWD"` plans statement insertion.
- `kast agent replace-declaration --symbol <fq-name> --kind function --content-file <snippet.kt> --workspace-root "$PWD"` plans declaration replacement.

Use `--inside-file` or `--inside-scope` for scope selectors, and use `--at`,
`--after-symbol`, or `--before-symbol` for declaration and implementation
placement. Add `--apply --idempotency-key <stable-key>` only after the plan is correct.

## Mutation Recovery

After a yield or disconnect, run `kast agent operation status --idempotency-key <stable-key> --workspace-root "$PWD"`; retrying the original request with the same key is also idempotent. Use `kast agent operation cancel` with the same selector to request cooperative cancellation. A filesystem fallback is safe only when retrieved terminal state proves edit application never started. Missing state after daemon restart is ambiguous; inspect the workspace instead of applying a fallback or using a new key.

With explicit `--workspace-root`, Kotlin target paths may be repository-relative;
Kast reports and sends their canonical workspace-contained paths.

## Health

Use this section only when a typed `kast agent` command fails, the user asks for
readiness evidence, or backend state is part of the task.

- `kast ready --for agent|kotlin|release|machine --workspace-root "$PWD"` is read-only readiness.
- `kast repair --for agent|kotlin|release|machine --workspace-root "$PWD"` is plan-only repair.
- Add `--apply` to `kast repair` only after the repair plan or readiness output asks for install-state mutation.
- `kast agent verify --workspace-root "$PWD"` proves backend health, runtime status, and capabilities for semantic work.
- On macOS, the IntelliJ plugin prepares workspace guidance and metadata; run `kast developer machine plugin` only to repair Homebrew-managed IDE plugin links.
- `kast developer runtime status --workspace-root "$PWD"` reports daemon lifecycle only.

Do not teach `kast agent tools`, `kast agent call`, `kast agent workflow`, `kast rpc`,
generated protocol paths, LSP capability internals, backend implementation classes,
portable instruction packages, Copilot package files, or hooks as public agent APIs.
