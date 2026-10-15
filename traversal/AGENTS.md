---
type: Module Guide
title: "Kast traversal"
description: "Defines bounded graph traversal and executes it through the existing relation operation."
resource: "repo://traversal"
tags: [kast, navigation]
timestamp: "2026-10-14T23:25:10Z"
source_revision: e014a489bfd7e69cbf2c4b61140ee771d857a03e
code_sources:
  - path: traversal/contract/build.gradle.kts
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalReferenceObservation.kt
  - path: traversal/service/build.gradle.kts
  - path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt
  - path: traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt
  - path: traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/ExhaustiveTraversalTest.kt
  - path: traversal/service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalExpansionDomainTest.kt
---

<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-14 | hash: 040393c2bd3c -->

# traversal

## Purpose

Defines bounded graph traversal and executes it through the existing relation operation.

## Key Files

- [TraversalPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt) - strategy, depth, budgets, and expansion domain.
- [TraversalContinuation.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt) - authority-bound frontier and remaining work.
- [TraversalPartialExpansion.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt) - partly expanded subjects and remaining work.
- [TraversalReferenceObservation.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalReferenceObservation.kt) - file-level occurrences without invented graph endpoints.
- [TraversalService.kt](service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt) - traversal admission and orchestration.
- [RelationBackedTraversal.kt](service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt) - one-hop reads and frontier transitions.

## Subdirectories

- [contract/](contract/) - plans, frontier state, progress, continuations, and outcomes.
- [service/](service/) - relation-backed traversal and its tests.

## Entry Points

- Gradle projects: `:traversal:contract` and `:traversal:service`.
- [ExhaustiveTraversalTest.kt](service/src/test/kotlin/io/github/amichne/kast/traversal/service/ExhaustiveTraversalTest.kt) - exhaustive traversal cases.
- [TraversalExpansionDomainTest.kt](service/src/test/kotlin/io/github/amichne/kast/traversal/service/TraversalExpansionDomainTest.kt) - domain constraints.

## Navigation Hints

- For omitted nodes, examine the depth, expansion domain, coverage, and partial frontier before changing enumeration.
- For the public `WALK` stage, trace query admission and projection into this service.
- The public `TRACE` stage has a fixed topology in the query engine. It does not expose arbitrary traversal depth.
- Read the [root guide](../AGENTS.md) for engineering rules and optional OpenWiki retrieval.
