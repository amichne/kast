---
name: kast
description: Use for compiler-backed Kotlin and Gradle discovery, reference indexing, symbol relationships, graph analysis, diagnostics, and validated changes.
---

# Kast

Use `kast` as the public interface for Kotlin and Gradle semantic work. Use the
private `kastctl` interface only to acquire, inspect, or release the exact-root
indexer workspace lease required by a mutation.

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
- Before a mutation, run `kastctl agent lease acquire --workspace-root "$PWD"`
  and retain its opaque `leaseId`.
- Use `kast change` to create a validated plan, then run
  `kast apply <PLAN_ID> --lease-id <LEASE_ID>`.
- Run `kastctl agent lease release --workspace-root "$PWD" --lease-id
  <LEASE_ID>` when the mutation session ends.

Do not infer semantic success from an empty result. Read `limitation` and the
suggested `next` commands when evidence is unavailable.
