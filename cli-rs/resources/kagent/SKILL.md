---
name: kagent
description: Use for compiler-backed Kotlin and Gradle discovery, reference indexing, symbol relationships, graph analysis, diagnostics, and validated changes.
---

# Kagent

Use `kagent` as the only interface for Kotlin and Gradle semantic work.

- Run `kagent` to inspect current workspace readiness and suggested next actions.
- Run `kagent up` to start or reuse the semantic runtime.
- Run `kagent refresh [PATH...]` after source changes.
- Run `kagent files [PATTERN]` to discover Kotlin source and script files.
- Run `kagent symbol find <QUERY>` to locate symbols, then use `show`, `refs`,
  `callers`, `callees`, `implementations`, `supertypes`, or `subtypes`.
- Run `kagent graph summary` for graph coverage and size. Use `topology`,
  `communities`, `cycles`, `bridges`, `neighbors`, `path`, or `impact` for
  structural and statistical questions.
- Run `kagent check [PATH...]` for compiler diagnostics.
- Use `kagent change` to create a validated plan and `kagent apply <PLAN_ID>` to
  apply it.

Do not infer semantic success from an empty result. Read `limitation` and the
suggested `next` commands when evidence is unavailable.
