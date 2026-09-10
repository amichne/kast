---
type: Runtime Flow
title: Semantic query
description: Query syntax is compiled into type-compatible stages, then evaluated under one lease and bounded resource accounting.
resource: file://query/service
tags: [query, symbol, source, relation]
timestamp: 2026-09-09T00:00:00Z
code_sources:
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
    symbols: [QueryPlanCompiler, AdmittedQueryPlan]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt
    symbols: [QueryExecutionState]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
    symbols: [QueryService]
---

# Semantic query

```text
query syntax -> plan admission -> candidate discovery -> exact refinement
             -> optional predicates/relations -> bounded result projection
```

The pure plan compiler prevents candidate-only and exact-symbol stages from being combined incorrectly. Execution uses a single request state to track the semantic lease, time, work units, encoded bytes, result capacity, limitations, and item failures.

Discovery may remain a candidate result. Exact-only operations force refinement, and failed refinements remain visible as limitations. Relation continuations and child budgets are derived from remaining parent capacity.

See [semantic read domains](../modules/semantic-reads.md) and [compiler identity](../glossary/compiler-identity.md).
