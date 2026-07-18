---
name: kast-codex
description: "Route Kotlin and Gradle semantic work through Kast's fixed, typed CLI surface. Use when Codex needs to discover Kotlin .kt or .kts files, resolve symbol identity, inspect relationships or impact, collect diagnostics, perform plan-first semantic edits, or recover a typed Kast operation."
---

# Kast for Codex

Use `kast --output toon agent ...` as the first route for Kotlin semantic reads and
mutations. Preserve the returned symbol identity, file paths, operation IDs, typed
failures, and diagnostics as evidence.

## Workflow

1. Acquire an exact-root `lease`; preserve its backend and opaque ID on every later
   semantic command. Never share it with another task or worktree.
2. Discover owned Kotlin paths with `workspace-files` before broad filesystem search.
3. Resolve a symbol with `symbol`; keep its fully qualified name, declaration file,
   declaration offset, kind, and containing type together as one identity.
4. Pass that identity to `references`, `callers`, `callees`, `implementations`,
   `hierarchy`, or `impact`. Continue paginated results without rediscovering identity.
5. Run `diagnostics` for each Kotlin file whose current contents matter to the task.
6. For a mutation, run `rename`, `add-file`, `add-declaration`,
   `add-implementation`, `add-statement`, or `replace-declaration` without applying
   it. Review the typed plan, then apply that same request with a stable idempotency
   key.
7. After interruption or uncertain completion, use `operation status` with the same
   idempotency key. Use `operation cancel` only when cancellation is the intended
   outcome.
8. Before finishing, rerun diagnostics for every newly changed Kotlin file, then
   release the same lease. Release is idempotent.

## Fallbacks

Use a generic Kotlin mutation only after the corresponding typed command has returned
an unsupported or typed-failure outcome for the same target. Keep the fallback scoped
to that target, retain the failure evidence, and report any remaining typed blocker
explicitly.

Do not infer success after an interrupted apply. Inspect operation state and the
workspace before retrying, and reuse the original idempotency key.

## Generated references

- Read [references/commands.md](references/commands.md) for the exhaustive exposed
  command table and evidence requirements.
- Read [references/examples.md](references/examples.md) for generated invocation
  examples.

These references are generated from the Rust exposure contract. Do not edit or
reconstruct their command inventory by hand.
