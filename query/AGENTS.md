<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-10-03 | hash: cb7b0326cfae -->

# query

## Purpose

Models and executes multi-stage semantic queries while retaining scope, budgets, and evidence through the plan.

## Key Files

- [QueryRowAdmission.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRowAdmission.kt) - pure symbol, occurrence, and binding row transitions and complete-right admission before effects.
- [QueryLocationFailure.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryLocationFailure.kt) - workspace-relative location refinement with closed path/offset failures.

- [DiagnosticCheckpointStore.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt) - detached diagnostic progress and immutable replay under bounded retention.
- [SourceRequestAdmission.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceRequestAdmission.kt) - source request predicates retain precise field and finite cause.

- [QueryDiscoveryTasks.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryDiscoveryTasks.kt) - undiscovered input, pending refinement, and monotone discovery witnesses within the existing interpreter.
- [PipelineCheckpoint.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt) - detached ordered stage tasks and distinct history.
- [QueryStateStore.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt) - one bounded lifetime and quota for execution checkpoints, immutable results, fitted pages, and exclusive publication claims.
- [QueryInvocationProjection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryInvocationProjection.kt) - sole retained-result capture and issuance owner after invocation completion proof; rejects required storage failure with preserved original coverage.
- [QueryOutcomeProjection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt) - canonical projection of semantic rows, qualification, retention, and result pages.
- [QueryRetainedResult.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt) - detached semantic rows, producer progress, coverage, and failures bound to one read basis, including original impact paths independently of the first output page.
- [QueryRows.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt) - closed symbol, reference occurrence, named binding, value-path, and retained witness rows with their semantic evidence.
- [QueryJoins.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt) - checkpointed equality index over retained symbol rows.
- [QueryJoinStage.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt) - join build and probe tasks within the query evaluator.
- [QueryRelationEvidence.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRelationEvidence.kt) - occurrence arrival facts and subject-linked relation omissions retained through composition.
- [QueryWalkEvidence.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryWalkEvidence.kt) - traversal record arrivals and page-local coverage, progress, strategy, and partial frontier evidence.
- [QueryWalkStage.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryWalkStage.kt) - query scheduling around the existing traversal operation and its typed continuation.
- [QueryWalkProjection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryWalkProjection.kt) - canonical projection of traversal records and observations into query results.
- [QueryReferenceTransport.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceTransport.kt) - detached token representation before canonical authority validation.

- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt) - query plan model.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt) - execution contract.
- [contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryOperations.kt) - query capabilities.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt) - execution orchestration.
- [service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt) - retained execution state.

- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) - host-independent read admission and dispatch.
- [protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt) - issued and restored reference authority.

- [QueryImpactSource.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactSource.kt) - source-basis producer and reviewed model admission with guarded independent peer targets.
- [QueryImpactPeerBoundary.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactPeerBoundary.kt) - reviewed final continuation to an independently completed exact peer site, preserving distinct full authorities and unresolved peer flow.
- [QueryImpactPeerSelection.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryImpactPeerSelection.kt) - pure exact peer-target selection and declaration inventory before native effects.
- [QueryImpactRequestedSite.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactRequestedSite.kt) - exact native site selection with its revalidation request and observed work grant.
- [QueryImpactSiteAccounting.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactSiteAccounting.kt) - original path links, proven scope exits, and required unresolved relationships for independently requested sites.
- [QueryImpactLedger.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedger.kt) - branch conservation and required obligation closure.
- [QueryImpactFinding.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactFinding.kt) - admitted original path ordinal and identity for compact retained witness projection.
- [QueryImpactRetainedGraph.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactRetainedGraph.kt) - request-local storage charging of shared immutable proof objects and their reference cells.
- [QueryPresentationExecution.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPresentationExecution.kt) - scoped pairing of value-path evaluation with encoded-page fitting; standalone execution retains its conservative byte guard.
- [QueryRelationObservation.kt](contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRelationObservation.kt) - actual relation request domain and coverage retained through composition.
- [QueryImpactTasks.kt](service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactTasks.kt) - bounded value-flow scheduling in the existing query interpreter.
- [QueryImpactSourceAdmission.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryImpactSourceAdmission.kt) - current producer and model acquisition under one shared grant.
- [QueryBoundaryPositionAdmission.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryBoundaryPositionAdmission.kt) - supplied model positions bound to freshly revalidated native sites.
- [RetainedQueryPresentation.kt](protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) - immutable result and impact witness slices without semantic replay.

## Subdirectories

- `contract` - sources, plans, operations, and execution types.
- `service` - query interpreter and support.
- `protocol` - shared canonical read admission, reference codecs, and evidence projection; see [query protocol](../openwiki/modules/query-protocol.md).

## Entry Points

- Gradle projects: `:query:contract`, `:query:service`, `:query:protocol`.

## Navigation Hints

- Start with the [repository knowledge](../openwiki/modules/semantic-reads.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- Begin with `QueryPlan`, then trace each stage through `QueryService` into symbol, source, relation, or traversal operations. A file-offset source discovers the containing named declaration and resolves it to an exact row inside the evaluator. Joins use retained symbol rows on the right, match canonical identity, and preserve both named output cells. `CanonicalQueryProtocol` restores result sources and execution checkpoints through `QueryStateStore`; presentation cursors read retained symbol, occurrence, traversal, binding, value-path, or impact witness rows without replaying stages. Impact runs retain exact producer invocations separately from their expansion domain; `QueryImpactLedger` preserves native reads, model applications, and unresolved obligations.
