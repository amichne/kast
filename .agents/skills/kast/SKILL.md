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
the hidden `kast skill` wrapper commands, resolves a compatible binary once at
session start, and warns when generic `view`/`grep`/`edit`/`create` reaches for
`.kt`/`.kts` source.

## Native tools (preferred path)

Each tool runs the corresponding hidden `kast skill` command, returns its JSON,
and validates arguments against a schema — no shell escaping, no JSON-in-bash.

| Need                                          | Tool                       |
| --------------------------------------------- | -------------------------- |
| List modules / source files                   | `kast_workspace_files`     |
| Understand a file or type (semantic skeleton) | `kast_scaffold`            |
| Resolve an exact declaration                  | `kast_resolve`             |
| Find every usage of a symbol                  | `kast_references`          |
| Trace incoming/outgoing call hierarchy        | `kast_callers`             |
| Indexed metrics (fanIn/fanOut, cycles, …)     | `kast_metrics`             |
| Rename safely (updates every reference)       | `kast_rename`              |
| Apply an edit and validate it atomically      | `kast_write_and_validate`  |
| Re-check files after a mutation               | `kast_diagnostics`         |

The extension caches the resolved binary path the first time a tool runs and
reuses it for the rest of the session. There is no bootstrap step to perform
manually.

If a tool or shell fallback reports `Unknown command topic: skill` or
`Unknown skill wrapper`, the resolved CLI is stale for this skill bundle. Build
or install the repo-local CLI, then retry with a binary whose `kast help skill`
does not report an unknown topic. The extension intentionally rejects stale
global binaries on `PATH` and prefers repo-local artifacts that support the
hidden skill wrappers.

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

See `references/recovery.md` for binary resolution order, bash fallback
invocations, and semantic query recovery guidance. See `references/quickstart.md`
for request/response field shapes and common request snippets.

## Maintenance

- `references/quickstart.md` — request snippets and per-command field shapes.
- `evals/catalog.json` — canonical behavior and routing cases; use `suite` to
  distinguish the routing subset from the behavior subset.
- `evals/pain_points.jsonl` — intake queue for newly observed regressions or
  misses before promotion.
- `evals/files/` — reusable fixtures referenced by catalog cases.
- `history/progression.json` — progression and promotion ledger for durable eval
  maintenance.
- `references/routing-improvement.md` — routing corpus workflow and promotion
  guidance.
- `references/wrapper-openapi.yaml` — checked-in wrapper contract snapshot.
- `scripts/build-routing-corpus.py` — sanitize shared logs and session exports
  into routing candidate cases; do not load during normal Kotlin navigation.
