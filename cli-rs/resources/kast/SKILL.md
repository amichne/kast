---
name: kast
description: Use for compiler-backed Kotlin and Gradle discovery, reference indexing, symbol relationships, graph analysis, diagnostics, and validated changes.
---

# Kast

Use `kast` as the only interface for Kotlin and Gradle semantic work.

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
- Run `kast check [PATH...]` for compiler diagnostics.
- Use `kast change` to create a validated plan and `kast apply <PLAN_ID>` to
  apply it.

Do not infer semantic success from an empty result. Read `limitation` and the
suggested `next` commands when evidence is unavailable.
