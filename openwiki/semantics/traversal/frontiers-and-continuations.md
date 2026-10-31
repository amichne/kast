---
type: System Explanation
title: Traversal frontiers and continuations
description: Explains bounded multi-hop traversal, frontier and visited state, partial
  relation expansion, exclusions, and exact continuation integrity.
tags:
- kast
- semantics
code_sources:
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalScopeExclusion.kt
- path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt
- path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt
- path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/OneHopRelationReader.kt
- path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalCallableObservation.kt
- path: traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/ExhaustiveTraversalTest.kt
- path: traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalReplayTest.kt
- path: traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalExpansionDomainTest.kt
sources:
  - id: openwiki-source-3654a7136517bb563193f636
    resource: repo://traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt
  - id: openwiki-source-cc6f3a62b7444ad290d11c73
    resource: repo://traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt
  - id: openwiki-source-ec5ef92fcdf435a952f8f6a6
    resource: repo://traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt
  - id: openwiki-source-499f17272ac32eeec4c5487f
    resource: repo://traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalScopeExclusion.kt
  - id: openwiki-source-f9e7416f74a1fd27f46c81b3
    resource: repo://traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-30T23:25:02.193Z
---

# Traversal frontiers and continuations

## One traversal composes one-hop relation reads

`TraversalPlan` binds an exact starting selector, relation meaning, strategy, extent, expansion domain, and resource grant.
The service admits that plan before the relation-backed engine starts.
Each frontier entry keeps its exact subject and depth.
Visited identity prevents repeated expansion under the same admitted traversal semantics.

Breadth-first and depth-first strategies choose the next legal frontier entry.
An exhaustive extent still obeys work, elapsed-time, result, and storage limits.
It does not promise unbounded native work.
The query `WALK` stage composes this service inside the existing query owner.
The separate `TRACE` stage uses its fixed query topology instead of a configurable traversal depth.

## Partial expansion is part of the result

A bounded one-hop read can leave a frontier subject partly expanded.
`TraversalPartialExpansion` keeps that subject, its depth, continuation, and unfinished evidence.
Progress records cumulative reads, edges, maximum depth, and checkpoint sequence.
The page cannot claim a fully expanded frontier when the current relation page still has a suffix.

Scope exclusions keep confirmed out-of-domain destinations with their original subject and authority.
They remain distinct from unsupported in-domain evidence and provider omissions.
File-level reference observations keep real occurrence evidence without invented graph endpoints.
Query projection must preserve these distinctions when traversal records enter retained results.

## Restore the exact frontier

`TraversalContinuation` binds the plan identity to the checkpoint's frontier, visited set, pending relation page, progress, and retained limitations.
Issuance computes a deterministic integrity fingerprint over that detached state.
Restoration rejects an identity or integrity mismatch.
A caller cannot change the scope, strategy, extent, or starting selector and reuse the old continuation as authority.

The next page consumes the pending native relation inventory before it advances to unrelated frontier work.
It keeps the original traversal's coverage and qualification.
The completion owner can claim exhaustive graph coverage only after the admitted frontier and pending work end.

Traversal tests establish extent handling, advancing state, exclusions, replay, and typed rejection.
They do not establish that an unexamined native provider supports every possible relationship in a user project.

## Focused tests

- [ExhaustiveTraversalTest.kt](../../../traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/ExhaustiveTraversalTest.kt)
- [TraversalReplayTest.kt](../../../traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalReplayTest.kt)
- [TraversalExpansionDomainTest.kt](../../../traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalExpansionDomainTest.kt)

## Source owners

- [TraversalPlan.kt](../../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt)
- [TraversalContinuation.kt](../../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt)
- [TraversalPartialExpansion.kt](../../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt)
- [TraversalScopeExclusion.kt](../../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalScopeExclusion.kt)
- [TraversalService.kt](../../../traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt)
- [RelationBackedTraversal.kt](../../../traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt)
- [OneHopRelationReader.kt](../../../traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/OneHopRelationReader.kt)
- [TraversalCallableObservation.kt](../../../traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalCallableObservation.kt)

## Read next

- [Named relations](../relation/named-relations.md)
- [Query composition](../../queries/execution-and-composition.md)
- [Strict completion](../../queries/trace-and-completion.md)
