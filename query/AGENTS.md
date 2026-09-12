<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-12 | hash: d2eb199966be -->

# query

## Purpose

Models and executes multi-stage semantic queries while retaining scope, budgets, and evidence through the plan.

## Key Files

- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt) - query plan model.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt) - execution contract.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt) - query capabilities.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt) - execution orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt) - retained execution state.

- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) - host-independent read admission and projection.
- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt) - issued and restored reference authority.

## Subdirectories

- `contract` - sources, plans, operations, and execution types.
- `service` - query interpreter and support.
- `protocol` - shared canonical read admission, reference codecs, and evidence projection; see [query protocol](../knowledge/modules/query-protocol.md).

## Entry Points

- Gradle projects: `:query:contract`, `:query:service`, `:query:protocol`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Begin with `QueryPlan`, then trace each step through `QueryService` into symbol, source, relation, or traversal operations.
