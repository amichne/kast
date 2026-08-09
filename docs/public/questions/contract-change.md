---
type: Explanation
title: What Must Change If This Contract Changes?
description: Turn a Kotlin contract change into a bounded semantic impact set.
tags: [impact, contracts, hierarchy, semantic-change]
code_sources:
  - path: cli-rs/protocol/source/commands.json
  - path: analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationExecutionResult.kt
  - path: cli-rs/src/semantics/repository_intelligence/contract/request.rs
---

# What Must Change If This Contract Changes?

This page shows how a contract change becomes a repository relationship
question before it becomes an edit.

## A local declaration can have nonlocal obligations

Adding a case to a sealed contract can affect implementations and exhaustive
consumers in different files:

```kotlin
sealed interface PriceResult {
    data class Priced(val amount: Money) : PriceResult
    data class Unavailable(val reason: String) : PriceResult
}

fun render(result: PriceResult): String = when (result) {
    is PriceResult.Priced -> result.amount.toString()
    is PriceResult.Unavailable -> result.reason
}
```

A text search for `PriceResult` finds obvious mentions. It does not classify
subtypes, constructor calls, type references, exhaustive branches, or callers
of the affected declarations.

## Impact begins with exact relationships

Kast can combine the contract's exact identity with implementations, hierarchy,
references, callers, and bounded incoming or outgoing impact. The result is a
semantic impact set tied to a workspace, source generation, depth, result
limit, and relationship projection.

That set answers a precise question: which compiler-visible declarations are
connected to this contract within the stated bounds? It does not mean that
every connected declaration requires the same edit.

## “Must change” requires proof after the edit

A validated change plan can preserve exact target identity and reject an
ambiguous or stale target before writing. Compiler diagnostics and mutation
postconditions can then show whether the changed sources remain semantically
valid.

The claim still ends at the admitted workspace. Binary consumers, reflective
lookups, generated sources outside coverage, configuration, and behavior-only
expectations need separate evidence. A bounded impact result is a safe planning
input; it is not an unqualified promise of repository-wide completeness.

The final question is whether the change
[reached every semantic dependency](verify-coverage.md).
