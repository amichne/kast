<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 040393c2bd3c -->

# traversal

## Purpose

Defines bounded multi-hop traversal plans/results and executes them over relation reads.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt) - traversal plan and limits.
- [contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalState.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalState.kt) - retained state.
- [contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalResult.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalResult.kt) - result model.
- [service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt](service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt) - orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt](service/src/main/kotlin/io/github/amichne/kast/traversal/service/RelationBackedTraversal.kt) - relation-backed execution.

## Subdirectories

- `contract` - operations, plans, state, and results.
- `service` - engine state, one-hop reads, and traversal execution.

## Entry Points

- Gradle projects: `:traversal:contract`, `:traversal:service`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Start with plan bounds and continuation state, then follow relation reads into `relation` or topology-backed handlers in `runtime/composition`.
