<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-15 | hash: fbcfe24f9532 -->

# query

## Purpose

Models and executes multi-stage semantic queries while retaining scope, budgets, and evidence through the plan.

## Key Files

- [DiagnosticCheckpointStore.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt) - detached diagnostic progress and immutable replay under bounded retention.
- [SourceRequestAdmission.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceRequestAdmission.kt) - source request predicates retain precise field and finite cause.

- [PipelineCheckpoint.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt) - detached ordered stage tasks and distinct history.
- [QueryStateStore.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt) - one bounded lifetime and quota for typed execution checkpoints and immutable results.
- [QueryOutcomeProjection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt) - canonical projection of semantic rows, qualification, retention, and result pages.
- [QueryRetainedResult.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt) - detached semantic rows, producer progress, coverage, and failures bound to one read basis.
- [QueryReferenceTransport.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceTransport.kt) - detached token representation before canonical authority validation.

- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt) - query plan model.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt) - execution contract.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt) - query capabilities.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt) - execution orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt) - retained execution state.

- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) - host-independent read admission and dispatch.
- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt) - issued and restored reference authority.

## Subdirectories

- `contract` - sources, plans, operations, and execution types.
- `service` - query interpreter and support.
- `protocol` - shared canonical read admission, reference codecs, and evidence projection; see [query protocol](../knowledge/modules/query-protocol.md).

## Entry Points

- Gradle projects: `:query:contract`, `:query:service`, `:query:protocol`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Begin with `QueryPlan`, then trace each exact-symbol stage through `QueryService` into symbol, source, or relation operations. `CanonicalQueryProtocol` restores result sources and execution checkpoints through `QueryStateStore`; presentation cursors read retained rows without replaying stages.
