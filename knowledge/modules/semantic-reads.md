---
type: Kotlin Module Group
title: Semantic read domains
description: Domain contracts refine discovery into exact compiler identity and compose source, relation, traversal, diagnostics, and queries without erasing evidence.
resource: file://query
tags: [kotlin, semantic, query, compiler]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt
    symbols: [SymbolDiscoveryService]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt
    symbols: [SourceSnapshot]
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt
    symbols: [RelationFact]
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt
    symbols: [TraversalPlan]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
    symbols: [QueryPlanCompiler, AdmittedQueryPlan]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
    symbols: [QueryService]
---

# Semantic read domains

Symbol discovery returns bounded candidates; exact resolution refines a candidate into compiler identity. Source reads, relation reads, traversal, and diagnostics consume exact, workspace-bound requests rather than re-parsing loose names.

The query domain provides a closed linear algebra over these operations. `QueryPlanCompiler` rejects type-incompatible stage transitions before execution. `QueryService` then interprets only an admitted plan and retains per-item failures and limitations instead of promoting partial work to completeness.

## Navigation

- Start in `symbol:contract` for identity and ambiguity.
- Start in `source:contract` for snapshots, coordinates, or visibility.
- Start in `relation:contract` for semantic edge meaning.
- Start in `traversal:contract` for bounded multi-hop state.
- Start in `query:contract` for cross-domain composition.

Read [semantic query](../flows/semantic-query.md) for execution order and [compiler identity](../glossary/compiler-identity.md) for the key refinement.
