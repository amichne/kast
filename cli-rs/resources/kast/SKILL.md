---
name: kast
description: Use for compiler-backed Kotlin and Gradle discovery, reference indexing, symbol relationships, graph analysis, diagnostics, and validated changes.
---

# Kast

Use `kast` as the public interface for Kotlin and Gradle semantic work.

- Run `kast` to inspect current workspace readiness and suggested next actions.
- Run `kast up` to start or reuse the semantic runtime.
- Run `kast refresh [PATH...]` after source changes.
- Run `kast refresh external <FAILURE_ID>...` only when an eligible file-local
  failure should remain as an explicit external `UNKNOWN` graph boundary.
- Run `kast files [PATTERN]` to discover Kotlin source and script files.
- Run `kast symbol find <QUERY>` to locate symbols, then use `show`, `refs`,
  `callers`, `callees`, `implementations`, `supertypes`, or `subtypes`.
- Run `kast graph summary` for graph coverage and size. Use `topology`,
  `communities`, `neighbors`, or `impact` for structural and statistical
  questions.
- When a result has `nextPage`, repeat the same `files`, symbol relationship,
  `graph nodes`, or `graph impact` command with `--page <nextPage>`.
- Run `kast check [PATH...]` for compiler diagnostics.
- Run `kast change` with `rename`, `replace`, `add-file`, or `add-declaration`
  to create a root-bound plan. Review its preview, proof, and limitations.
- Run `kast apply <PLAN_ID>`. Kast owns the workspace lease, revalidates the
  plan, applies it, and verifies the postcondition before it returns a receipt.
- Treat only `VERIFIED` as success. Read `REJECTED`, `CONFLICTED`,
  `ROLLED_BACK`, or `RECOVERY_REQUIRED` as typed non-success outcomes.
- After `RECOVERY_REQUIRED`, run `kast recover <RECOVERY_ID>`. Recovery can run
  in a new process and either verifies the intended result or restores the
  exact source pre-state.
- Retrying a terminal plan or recovery receipt does not repeat source writes.

For setup, runtime control, local-state inspection, raw RPC, or release work,
invoke `/kast:developer`. Read `developerOperations.cli` from `kast`; do not
assume `kastctl` is on `PATH`.

Do not infer semantic success from an empty result. Read `limitation` and the
suggested `next` commands when evidence is unavailable.
