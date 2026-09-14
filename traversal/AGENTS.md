<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-14 | hash: 1ede91fb0fe0 -->

# traversal

## Purpose

Defines bounded multi-hop traversal plans/results and executes them over relation reads.

## Key Files

- [TraversalProgress.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalProgress.kt) - cumulative progressing checkpoint evidence.
- [TraversalPartialExpansion.kt](contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt) - page-local partially expanded subjects and remainder evidence.
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

- Start with plan bounds and continuation state, then follow relation reads into `relation` and hosted request admission in `query/protocol`.
