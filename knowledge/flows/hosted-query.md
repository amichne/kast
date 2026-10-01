---
type: Runtime Flow
title: Existing-IDE semantic query
description: An existing IDEA project owns five canonical read operations, with bounded live authority and scoped native CLI/provider acceptance.
resource: file://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted
tags: [intellij, kotlin, semantic-query, lifecycle]
timestamp: 2026-10-01T00:00:00Z
code_sources:
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEpochStore.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadFreshnessOwner.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedProjectAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedGradleChangeTracker.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedPublicationDeadlineTest.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadFailureReports.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedContainmentReportTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedContainmentBudgetTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/ide/HostedReportAdmissionTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadAllowanceIdentityTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedContinuationOwnerRetentionTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryUnsupportedIdentityTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryCheckpointReplayTest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmission.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryLifetime.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedTransportObservation.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceQualifiedProgressDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceReadOutcomeDocuments.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceProgressProjection.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourcePreparedCoverage.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryRunQualification.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryQualifiedProgressDocument.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryProgressProjection.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPreparedCoverage.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetReport.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionLimitDocument.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetReports.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/QueryWalkWireDocuments.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/SourceReadOutcomeWireMappings.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/QueryWalkCliDocuments.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceExecution.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityAttempt.kt
  - path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourcePageCollectorTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceProtocolBudget.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetDocument.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedExecutionBudgetRequest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetAdmission.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmissionTest.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationEffect.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticPublicationSession.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceStateStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyGraph.kt
    symbols: [sourceDependencyClosure, sourceExpiredEntries]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalSource.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourcePublicationSession.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyExpiryTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceClaimExpiryTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyBindingTest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDeadline.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
  - path: docs/hosted-read-configuration.md
  - path: experiments/host-observation/reproduce_semantic_queries.py
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDiagnostics.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadGauge.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadObservation.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryService.kt
    symbols: [HostedQueryService]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedSemanticReadContext.kt
    symbols: [HostedSemanticReadContext, HostedLiveReadAuthoritySession]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadTransaction.kt
    symbols: [runHostedReadTransaction]
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijProjectSourceMembership.kt
    symbols: [IntellijProjectSourceMembership]
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireBinding.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedEpochVfsDiagnostics.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedVfsObservation.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedVfsDiagnosticReceipt.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointReclamation.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
    symbols: [evaluateHostedCanonicalQuery]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointProtocol.kt
    symbols: [HostedRequest, HostedRequests]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerCancellation.kt
    symbols: [HostedPeerTermination, dispatchUntilPeerTermination]
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedPeerCancellationTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/ProjectBoundIntellijSymbolPorts.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/ProjectBoundIntellijSourceReadPort.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/ProjectBoundIntellijRelationPort.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundIntellijDiagnosticPorts.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijSearchScopeCompiler.kt
    symbols: [CompiledIntellijSearchScope, IntellijScopePopulation]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijDiscoveryPackageAdmission.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationPackageAdmission.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeQuery.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/ide/ExistingIdeSocketClient.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/AdmittedHostedQuery.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/LiveHostedKotlinRead.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/LiveHostedClassIndex.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/LiveHostedIndexedSupertype.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQualifiedClassSelection.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedClassLookup.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedSourceScope.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryExecutor.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadCheckpoint.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryProbe.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedQueryWire.kt
  - path: workspace/intellij-read/build.gradle.kts
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/NamedGradleSourceScope.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveNamedGradleSourceScopeCapture.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/GradleBuildProjectIndex.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/SelectedGradleBuild.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/NamedGradleModuleScopeCapture.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/LiveSelectedGradleModuleRoots.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IdeRootMappingFailure.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/NamedSourceScopeFailureDocument.kt
  - path: experiments/host-observation/run_hosted_query.py
  - path: experiments/host-observation/hosted-query.kts.template
  - path: experiments/host-observation/hosted-plugin-unload.kts.template
  - path: experiments/host-observation/hosted-project-restoration.kts.template
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/IndexingWait.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnection.kt
  - path: experiments/host-observation/kast_ide.py
  - path: experiments/host-observation/qualify_hosted_index.py
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeApply.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedPreWriteObservation.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeFailure.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedResponse.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceEntityTraversalState.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt
  - path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/SourceCursorReadTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateRecords.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/DiagnosticProgressDocument.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionMeasurements.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticStateRecords.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticRetentionOwnership.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceRetentionAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionOwner.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt
---

# Existing-IDE semantic query

## Default canonical read path

The connected provider admits hosted tool requests and selects the exact enrolled workspace. The daemon prepares the selected IDEA project, then sends one native read to that project's endpoint. An absent host rejects without starting an isolated worker or falling back to a direct CLI socket.

The ordinary-query scope gate has a separate project-bound capture for exact
imported Gradle names. It reads `ExternalProjectDataCache` and joins explicit
`ExternalSourceSet.name` facts with the current IDE source folders. One read admits
one Gradle build. Module Gradle awareness and normalized external build identity
are checked before source/resource folders. A different external build root is
excluded without inspecting its project or source roots.

Composite imports can report the outer import root for every module. The cached
`ExternalProject` hierarchy retains build-local project paths: each `path == ":"`
node starts a build authority at that project's directory. `GradleBuildProjectIndex`
retains that authority through child projects and resolves the module's external
project directory before admitting `AdmittedSelectedBuildModule`. Included build
projects cannot become selected-build owners merely because they share an import
root. No directory names or filesystem containment establish build ownership.

Only admitted modules reach `LiveSelectedGradleModuleRoots`; foreign roots cannot
contribute entries, exclusions, root-budget usage, or mapping failures. Missing
ownership still rejects. A declared folder without a physical directory is
counted and excluded from IDEA roots; it does not reject a selected build. An
absent folder that is not declared by Gradle, an unsupported kind, a missing
cached owner, or a classification mismatch still rejects with bounded module/root
evidence. The hosted failure DTO and diagnostic outcome preserve that evidence,
including explicit truncation and both sides of classification mismatches.
Separate selected/foreign module counters describe successful admission decisions.

An imported model with no source roots admits a complete empty scope. Missing or
inconsistent ownership rejects before name filtering. Most-specific roots win,
including generated, excluded and resource roots; an unknown nested source-folder
kind rejects rather than inheriting an allowed parent. This gate performs no import
or sync.

The symbol scope compiler retains a typed `IntellijScopePopulation`. After valid
owner and source/generated-policy roots have been established, an exact source-set
name absent from all roots of that owner produces `KNOWN_EMPTY` and an empty native
scope. Unknown ownership and policy-excluded roots still reject; library inclusion
cannot broaden the known-empty intersection. Symbol and relation native scope
membership checks use paths, model ownership, and the file index.
`IntellijProjectSourceMembership` keeps source membership and exclusion observation
in the workspace adapter; semantic adapters consume that native predicate. Package evidence
is read after bounded provider collection or exact PSI lookup, outside native index
callbacks. Exact restoration and relation subjects and targets reapply the retained
package restriction at that point.

`HostedQueryService` can now provide an owner-bound `HostedSemanticReadContext`
with current live authority, captured model, exact source-file admission, and
validation. The generalized read transaction checks authority before and after
evaluation, and ends its context before returning. Saved documents and committed
PSI remain live obligations. Original-owner retirement and epoch movement reject
references instead of falling back to a publication or another IDE.

`HostedCanonicalQuery` composes three public canonical reads: `query.run`,
`source.read`, and `diagnostic.check`.
Query expansion supplies one-hop relation facts through occurrence output;
query walk composes the traversal domain operation and projects depth-bearing
records and coverage. Retained result rows project typed bindings for later query
stages. The host supplies pure services, project-bound
ports, current-model reference restoration, and explicit budgets to the reusable
[`query:protocol`](../modules/query-protocol.md) boundary. Query evaluation keeps
one request budget across its stages; specialist request limits intersect bounded
host caps. Evaluation and wire projection share the admitted read lifetime and
the executor's whole-request deadline. Output carries live evidence without a
workspace generation.

Declaration name and containing-location queries use the admitted project's
authored source scope. Exact references carry the owner and freshness checks
through query and source read admission.

`HostedConnectionAdmission` admits up to `HOST_CONNECTIONS` concurrent frame exchanges
and dispatches (default 16); the native accept backlog is `HOST_ACCEPT_BACKLOG`
(default 64). One further connection may receive a bounded capacity rejection
without semantic admission. There is no whole-request semantic mutex or semantic
queue. A blocked frame occupies one connection slot without blocking other reads.
`HostedQueryLifetime` independently bounds live read permits by `HOST_READERS` (default 2),
including direct in-process callers. Each request owns its progress,
deadline and cancellation cleanup; its permit remains occupied until its work drains.
Cancellation of one request neither releases nor cancels another request's permit.
Mutation application and recovery retain their coordinator's exclusive admission,
and IDEA retains native write exclusion.

Parallel readers can overlap evaluation. Epoch authority admission, store rotation,
and endpoint retirement are short synchronized ownership transitions. Native read
access is acquired before the authority monitor; current epoch revalidation and
authority admission occur together after model capture. A delayed older request
cannot replace newer reference or continuation storage. Store retirement is terminal.
The connected provider and App Server remain persistent JVM processes; ordinary
hosted calls use a direct Unix socket exchange and do not start a JVM per request.
The [throughput evaluation](../../docs/reviews/semantic-read-throughput.md) records
the historical serialized baseline. The [native concurrency qualification](../../docs/reviews/concurrent-semantic-reads.md) records the measured reader-capacity choice.

`kast_transport` records a per-connection correlation ID, finite stage/outcome,
monotonic stage duration, and observed byte counts. Stages cover accept, request
read, semantic admission, execution, response preparation, reply write, and
connection release after the admission permit is returned.
These records contain no source, request payload, or opaque reference.

`HostedPeerCancellation` races request dispatch against peer disconnection or
additional input after the one admitted frame. Both jobs are cancelled and joined
before the request leaves its scope. The semantic executor separately drains its
work before releasing the permit, so cancellation cannot leave an earlier request
running beside its replacement.

The existing-IDE client decodes each response through its canonical
operation binding, preserves complete, qualified, and rejected outcomes, and
requires successful live evidence to match the requested root and descriptor host.
Source snapshots must repeat the exact enclosing live evidence or published
generation; live traversal roots must match the enclosing root. Both encoding and
decoding reject contradictory evidence before CLI projection.
It rejects a publication envelope on this path. Typed host rejection remains
available when admission fails before operation authority exists.

The [native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the installed plugin and final default-route CLI matrix on IDEA
262.10315.125. Exact queries, specialist discovery/inspection, source, relations,
and diagnostics completed. Broad `ALL` remained work-limit qualified and traversal
retained its depth qualification. A real production provider invocation completed
`SEARCH Child` with the same item and live evidence as direct CLI execution.
Saved edits, stale references, peer disconnection, project retirement and actual
plugin unload have separate native receipts. Full Codex WebSocket integration,
complete broad enumeration and library parity remain unqualified. The history
below concerns the earlier bounded class/supertype routes; it is retained as a
separate qualification record.

## Earlier qualified hosted reads

The project-level service retains one admitted existing Project, an owner-scoped
epoch source, and a terminal endpoint lifetime. It captures the cached Gradle
model without import, joins the same epoch to cancellable reads, and uses K2 to
prove that the selected class directly inherits its one explicit supertype.
Compiler identities and canonical signatures reuse symbol and protocol contracts.

Both endpoints must have uniquely owned supported source folders. Global dirty
documents, project-uncommitted PSI, indexing, changed stamps, and lost freshness
reject the request. The response explicitly describes saved IDE content and
cached source-folder provenance. This request-local snapshot does not establish
the historical [workspace publication](workspace-publication.md).

Semantic objects stay inside the read lifetime. The executor has one active
permit and a cooperative deadline; cancellation drains before releasing the
permit. Detachment invalidates the original endpoint, drains its requests, and
disposes its listener owner. No opener, importer, or isolated worker is used.

The versioned production plugin archive contains compiled adapter code and its
contract dependencies. Stable release installation verifies the archive checksum,
plugin identity, release version, and IDEA `262` release line before atomically
activating it in the selected IDEA user plugin directory. Runtime admission accepts
IDEA and bundled Kotlin patch builds within that line and retains their full
observed identities. This broader policy does not expand the recorded native
acceptance evidence. The earlier manual proof archive still targets its exact build. The [manual runner](../../experiments/host-observation/HOSTED_QUERY.md)
loads that artifact into an existing native IDEA process and records project,
model, read-lock, result, and retirement evidence. Ordinary library builds have
no plugin descriptor. Opt-in checkpoints after semantic detachment qualify
restored edits, cancellation, and original-owner disposal before publication.
The project-close case explicitly restores the selected project after the old
request retires. The plugin-manager case uses the actual injected project
service and verifies dynamic unload. Full restart startup and upgrades between
different plugin versions remain unqualified.

The controller refines acceptance evidence into typed verification or rejection
outcomes even under Python optimization. It records bounded carrier outcomes
separately from semantic results, so a script that never enters the host cannot
be mistaken for query completion or owner retirement.

Exact class-name discovery reads the existing Kotlin short-name stub index in
the same admitted project. Candidate collection ends before K2 resolves each
class and detaches its canonical compiler identity. The shared read boundary
checks saved content and one epoch before publication. Queries are restricted
to supported cached authored source folders, bounded to 32 candidates and 64 KiB
of output, and reject overflow. No index storage is copied or rebuilt by Kast.

Direct-supertype reads can select a class by its qualified identity through the
existing full-class-name index. A bounded, complete collection must contain
exactly one declaration before PSI and K2 resolution begin. Missing or duplicate
declarations are closed failures; the detached compiler identity must match the
requested class identity. Index selection and semantic proof share the same
admitted read and final content/epoch checks. The explicit file/offset selector
remains available for the manual acceptance harness.

The [persistent endpoint](../../experiments/host-observation/HOSTED_ENDPOINT.md)
is owned by the separate `runtime:hosted` plugin. Normal requests use a framed
Unix socket and retain the same packaged compatibility policy and admitted epoch
authority across requests. The native acceptance client rejects missing hosts
without opening an isolated workspace. Public semantic discovery uses the
canonical provider operations; the duplicate `kast ide classes` and
`kast ide supertype` commands have been retired.
After an IDE crash, the next endpoint owner may reclaim the paired socket and
descriptor while holding the exclusive ownership lock. Reclamation requires the
recorded PID to be absent, the descriptor to name this root and exact socket,
and both protected artifacts to retain their admitted physical identities.
Protocol, schema and operation catalogs are capabilities rather than ownership
proof, so a dead endpoint from an older plugin can be retired after upgrade.
Live or reused PIDs, malformed owner identity, symlinks and unpaired artifacts
fail closed. Descriptor admission, overall admission and retirement emit bounded
stage/outcome observations.

The hosted service also records bounded VFS observations under its own lifetime.
Receipts contain event kind, IDE-versus-refresh origin, syntactic path categories,
counts and the existing host correlation. `OUTSIDE_ROOT` counts expose global
VFS activity that can schedule project indexing. They contain no file paths or
source payloads. These diagnostic categories do not change source membership or epoch
admission; the original epoch listeners remain authoritative. Oversized diagnostic
batches emit `relevance_unknown` with reason `BATCH_LIMIT`, without path categories
or relevance proof. The epoch listener independently advances its invalidation
signal conservatively; a diagnostic receipt is not proof of fresh read admission.

Incremental creation, class renaming, and deletion were qualified against the
same original IDE index. The current CLI-to-daemon route has local contract and
socket evidence; native desktop composition and stronger workspace publication
remain separate integration boundaries.

The qualified supertype selector remains in the hosted endpoint contract for
native and compatibility tests. Index maintenance remains with IDEA.
The shared schemas and operation registry live in `protocol:contract`; the CLI
loads those resources directly from its dependency. Production packaging no
longer reads protocol assets or host properties from the acceptance experiment.

The opt-in [semantic reproduction runner](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md) separates fixture creation/import, runtime pinning, and read-only public CLI/provider replay. Its public requests use the shared `type` discriminator and `CAPS_CASE` enum values, and validate against the installed full tool schema before replay. The hosted service publishes one bounded native diagnostic receipt by default after a request drains, including effective configuration/provenance, stage durations, remaining outer deadline, contributor counts and precise termination reasons. Diagnostics carry only finite categories and bounded counts; they do not alter budgets or strengthen qualified coverage. An epoch change invalidates cross-request reference evidence.

## Hosted change admission

Endpoint protocol 3 retains the read routes and adds change planning, approval
preparation, apply and recovery. The CLI rejects older endpoint descriptors.
`AddDeclaration` planning consumes the exact live selector from these reads;
apply obtains a fresh read and a pre-write observation before entering the
IntelliJ write command. Dirty documents, changed epochs and unavailable authority
reject without isolated-worker fallback. Plan and receipt observations remain
historical evidence, separate from the read authority needed for a new effect.
See [change lifecycle](change-lifecycle.md) for approval, verification and recovery.
The public [declaration change guide](../../docs/public/change.mdx) describes the
saved-project workflow and the qualified states an agent must preserve. Change
verification establishes new live evidence after the write; it does not reuse
the planning epoch or publish a workspace generation.

## Corrected native discovery and relation expansion

The subsequent [semantic reproduction review](../../docs/reviews/hosted-semantic-reproduction.md) records complete fixture `ALL` queries under unchanged default budgets. Scoped Kotlin file indexes supply declarations directly; exact names retain their direct indexes and project-only fuzzy discovery uses the same scoped file enumeration. Constructor properties refine through their generated K2 property symbol. Query `EXPAND` retains the original selected subject while admitting related endpoints in the workspace search boundary. Java references resolve through K2 identity, and explicitly excluded library calls do not make project-only callee coverage incomplete. The earlier acceptance limitations above remain historical evidence.

The read policy is immutable per host service and rejects invalid settings. CLI/provider transport capacities use the same typed parameter catalogue. See [read configuration](../../docs/hosted-read-configuration.md).


The new `tool` CLI family lowers the five [public intent tools](../contracts/public-tools.md) into the existing canonical operations before taking this same existing-IDE path. Native semantics and reference authority remain here; tool syntax is not compiler evidence.

The plugin-only runtime retirement removes isolated composition/import from the active build and topology publication from the shipped runtime graph. Topology modules remain buildable for upcoming graph work. Earlier acceptance records above remain historical. The shared `HostedSemanticServices` factory now supplies canonical reads, planning and verification inside each admitted read context. Diagnostic schema 5 distinguishes transaction evaluation from complete, qualified and rejected semantic results, retaining bounded stage and termination evidence. It records the first entry to each native phase before an opaque call, plus cumulative phase durations after drainage: source-partition preparation, declaration scan, exact refinement, reference inventory/partition/confirmation, diagnostic scope admission/enumeration/K2 analysis, freshness validation, retention, encoding, and cancellation drainage. Counters distinguish candidate and semantic work from retained-state accounting; the latter is a quota estimate rather than process heap measurement.

The host defaults to 30,000 ms and derives positive semantic and diagnostic-scope
allowances from the time remaining after admission and model capture. A bounded
completion reserve precedes the hard deadline. Exhaustion rejects before semantic
evaluation, while a cooperative time-limited query can publish qualified results
after freshness revalidation. Receipts distinguish configured limits from effective
allowances. Work that exceeds the hard deadline still cancels and drains.
Configuration also requires client exchange time to strictly exceed host connection
time, and both provider invocation deadlines to strictly exceed client exchange
time. These outer boundaries retain positive IPC slack even when operators lower
their settings; semantic/host configuration equality still uses the completion reserve.
An indexing transition rejected before semantic evaluation may wait for smart mode
and retry once at hosted dispatch. Once admitted, a canonical read evaluates against
one epoch. A moved epoch rejects the result; dispatch does not replay evaluation
against another epoch. The caller reruns the original request. Continuations and
compact references do not migrate between epochs.

Typed request decoding rejects a supplied returned-byte allowance below the wire
owner's serialized schema/operation identity size before semantic dispatch. This is
a necessary lower bound, not an exact sufficient envelope size. The original
encoder still fits bodies, reports and continuations against the admitted allowance.

`HostedQueryContinuations` owns one `QueryStateStore` for detached query execution
checkpoints, immutable retained results, detached transport suffixes, and publication claims under the same entry, byte and lifetime
limits. Epoch replacement and project
disposal clear the state. Resume takes only an issued continuation plus an optional
new grant; the stored checkpoint supplies the admitted plan and pending tasks.
Restored results require the same semantic basis, while read-result uses its own
presentation cursor and no semantic provider call. Host admission precedes each
lookup. No retained entry holds PSI, K2 symbols or an IDE observer callback. The
serialized output budget is checked after compact exact references and selected
projection fields are encoded.
When a result is retained, the store issues row IDs scoped to that result and the
query projection returns them with its rows. A composed run can select issued
symbol rows without turning their IDs into exact-symbol references; binding
rows remain readable as typed retained results.

Transport output cursors are replayable until expiry or eviction. Equal retained requests
and outcomes have equal child identities; replay does not refresh expiry. The
request identity retains the semantic request while excluding caller execution
controls. Changed authority or semantic request is rejected. Each of the
query state, source output, and diagnostic checkpoint owners has its configured entry/byte
bound. Query and diagnostic output suffixes use their respective execution owners; they no longer have parallel output stores. No retained entry contains PSI or K2 state.

Query/search reads admit optional caller execution controls once at
semantic entry.
`HostedReadDeadline` subtracts elapsed request time and its completion reserve;
`HostedSemanticTimeAllowance` retains the resulting immutable grant. Domain
budgets derive their resource values from that grant. Their response metadata
projects its effective values and clamping causes, and byte fitting includes the
metadata. Retained query and source suffixes omit previous-call grant metadata; resume
publishes the newly admitted grant and excludes execution controls from retained
semantic identity.

Reissuing an equal detached query checkpoint returns its existing token without
renewing its expiry or consuming another entry. Admission claims one unfinished token exclusively, while published pages replay their facts and successor without semantic execution. Concurrent use returns a finite in-use rejection. Allocations remain hidden until the fitted page commits after final host validation and native drainage. Cancellation, rejection, moved-read restart, and retired authority discard only the current attempt's allocations. Query, source and diagnostic owners check original token age independently of physical retention. Live pages pin dependencies against capacity eviction under the same quota; their validity ends at the oldest referenced dependency's deadline. Active claims preserve physical storage until release without renewing expiry, admitting an expired token, or publishing an expired dependency. Replay never renews page age.
Query checkpoints and immutable results share one quota but retain distinct typed
references. Query pipeline checkpoints and output suffixes exclude caller execution
controls from semantic identity. Query page result limits also constrain retained
output; every page preserves known item failures. A query `take` stage is not
admitted. Source and traversal caller controls retain the same admitted report;
traversal evidence is scheduled in bounded structural and occurrence units before encoded fitting.

Source entity cursors retain typed token keys and exact snapshot, region,
selection identity, and detached structural frontier. Their binding excludes entity/text page allowances; an equal
source cursor position reuses its token. The native cursor proof and encoded
suffix share one hosted state owner; native entity collection retains no registry.

The source owner applies shared entry, charged-byte, and original-age bounds to detached cursor proofs, suffixes, published pages, and claims. Admission, deduplication and publication validate the complete dependency closure against the same semantic request and authority. A running cursor cannot become another attempt's deduplicated successor. Expiry preserves active claims' physical dependency storage, then removes expired entries and broken dependent pages that no active claim owns. A retained expired dependency rejects publication as `EXPIRED`; an absent or unavailable dependency rejects as `DEPENDENCY_UNAVAILABLE`. Oversized entries fail before issuance; retirement clears every entry.

Source requests require an explicit resource grant. Hosted source admission retains the same immutable resource object and intersects entity/text projection limits. Query visibility predicates transfer one charged unit and the remaining elapsed allowance into their exact SELF source read; failed admission retains the unstarted pipeline task. Native source enumeration now charges visited PSI units against that grant and checks monotonic elapsed time before each unit and at completion. Accounting survives canceled read attempts, while their PSI and detached result buffers do not. Source encoding now fits the complete encoded envelope, including report and cursor, before publishing a nonempty entity prefix. Its `source-output:v1` cursor retains the detached suffix and original upstream qualification under the smaller of hosted `QUERY_CONTINUATION_*` and native `SOURCE_CONTINUATION_*` bounds in the same owner. Source output identity retains anchor, region, entity selection, and text projection but excludes entity/text page allowances and execution controls. Every page retains requested text; an indivisible item or mandatory envelope that cannot fit is rejected without an empty unchanged cursor.

Source and traversal result projections preserve their admitted execution report through canonical wire decoding and CLI output. Their complete and qualified envelopes share the same closed installed execution schema. Hosted encoding measures the full response, including this report, against the current grant.

Canonical source, traversal, and query semantic rejections now retain
the current hosted grant in operation-owned admitted failure variants. Their
wire/CLI documents preserve the original finite reason and add a sibling
`execution_budget`; clearing retained output payload reports cannot erase an
admitted rejection's proof. Pre-admission rejection documents omit the field.
The executor also retains an actually admitted report through hard timeout,
platform/cancellation/lifetime refusal and final freshness rejection. Hosted read
failure documents preserve the finite cause and stage with that report. Oversized,
encoding and retention refusals preserve the same report when publishing a read
outcome fails; their internal semantic evidence remains attached. Rejections
before semantic admission, including transport admission failure, omit the report.
No failure path reconstructs a default grant to fill missing evidence.

`max_returned_bytes` bounds canonical semantic output; the operator's
`HOST_RESPONSE_BYTES` bounds each hosted frame payload, including a terminal
containment rejection. Neither cap is raised for a failure. Before recording a
semantic grant or calling its evaluator, publication admission encodes the typed
containment witnesses with the calculated candidate report and checks that they
fit the hard frame cap. It checks the candidate and a second typed allowance
whose remaining time is the minimum of the current effective elapsed value and
one less than the caller-selected/default elapsed value. This witnesses the
largest effective value carrying a deadline clamp, including when an operator
ceiling keeps its digits unchanged. Selection of one millisecond needs only the
candidate because no smaller positive remaining time can add a deadline clamp. After those checks, it observes remaining host time
again and re-admits both semantic and diagnostic allowances before recording the
grant. Exhaustion during publication checks returns `BUDGET_EXCEEDED` without
provider access and records `exhausted`, distinct from capacity rejection.
`HostedPublicationDeadlineTest` covers this ordering, decimal/clamping boundaries
and maximum positive input values. Runtime admission also checks every finite endpoint
failure encoding. The freshness witness is checked against every closed freshness
cause and stage in a focused test. Insufficient capacity returns the existing
`RESULT_LIMIT_EXCEEDED` without an executed report. Its bounded
`publication-rejected` diagnostic retains the candidate, distinct from an
`admitted` observation. A consistent 256-byte host/semantic/source configuration
proves zero provider calls and a fitting pre-admission rejection; normal capacity
proves report-bearing publication. This guard does not promise that a semantic
result, even an empty one, fits a caller's output allowance.

The packaged hosted schemas admit this optional, non-null report from the same
`ExecutionBudgetReport` descriptor used by canonical reads. The CLI additionally
decodes it through report refinement, rejecting dimensionally impossible clamping
as well as unknown or structurally invalid fields. `HostedContainmentReportTest`,
`HostedContainmentBudgetTest` and `HostedReportAdmissionTest` cover the executor,
actual encoded failure documents and the external admission boundary. These are
controlled local checks; installed containment/report qualification is separate.

Execution-limit reports refine positive numeric amounts and validate caller/default selection, effective bounds, and canonical clamping before decoded fields become report evidence. Private construction prevents a report copy from bypassing those relationships.

Native source enumeration admits declaration kinds before deferred visibility and
candidate projection. Excluded containers preserve eligible descendants. The
source cursor owns detached sibling/child tasks, provider descriptors, parent
proofs, a same-node parameter phase, and proven lookahead. Each successor
reacquires those locators directly instead of replaying delivered PSI or compiler
projection. Structural revision advances even when a page has no newly eligible
entity; emitted ordinals never decrease. Semantic omissions remain in the
frontier while discharged page work/time limits do not falsely qualify final
exhaustion. Each read-action attempt creates a fresh collector and retains the
request execution meter. Cancellation discards that attempt without resetting
its allowance. Physical PSI regressions cover 121 declarations at result limits
1, 5 and 20 under work 32, excluded-container progress and same-node phases;
installed K2 execution remains a separate qualification boundary.

Decoded execution reports also retain their dimension rules: elapsed limits admit deadline clamps; result and byte limits admit transport clamps; work limits admit only the operator ceiling. Every result amount remains within the integer domain. Invalid dimension evidence is rejected by the report decoder before a report value is exposed.

Query qualification owns mandatory closed execution progress: resumable with an upstream checkpoint or retained-output checkpoint, or terminal-incomplete with a finite reason. A retained-output checkpoint reports the original upstream coverage, preserving terminal reasons without asserting that an interrupted scan can resume. An upstream page that advances retained work can resume under the same grant even when downstream filtering emits no rows. `QueryRunResult` separately reports retention outcome and an optional result presentation cursor. That cursor pages immutable retained rows; it is not an execution continuation. CLI compatibility fields for execution progress are derived from qualification. Wire decoding rejects missing progress and noncanonical checkpoint families.

Source qualifications own closed resumable, retention-unavailable, or terminal-incomplete progress. Native
source checkpoints and hosted retained-output checkpoints are separate variants;
retained output preserves original complete, resumable, or terminal coverage.
Legacy cursor availability is derived from that authority. Empty native pages
may resume structural progress under the same grant. A retention-unavailable
page preserves its fitting facts and original upstream coverage without a token. A terminal text-withheld explanation
requires a matching text-byte limitation; other upstream gaps remain finite
terminal evidence. Source wire admission rejects missing progress, unsupported
variants and mismatched checkpoint families.

### Hosted continuation retention bounds

Each `HostedQueryContinuations.Active` owns three bounded state stores: query
execution/results/output, source native cursors/output, and diagnostic scan/output.
Each domain's claims and publications use its existing owner and quota. The source
owner applies the smaller of source and query entry, byte, and lifetime bounds.
There is no independent output-suffix quota or native source registry alongside
these owners. Charged bytes estimate detached retention; they do not measure JVM
heap use. Requests, locators, semantic rows, omission evidence, claims, cached
pages, and output suffixes contribute to their owner's accounting. Source and
diagnostic gauges retain independent current bytes, entry counts and maximum
byte high-water marks rather than adding snapshots as work counters.

A retained published page pins the work it advertises. Attempt claims isolate
new allocations until successful final publication. Commit releases superseded
producer payloads, keeps the immutable fitted page and advertised dependency
closure, and prunes unadvertised children. Cancellation releases
only that attempt's allocations after drainage. Replaying an available published
page returns its immutable successor without native prefix work or renewing its
creation time. Query entries expire strictly after TTL; source and diagnostic
entries retain their exact-boundary expiry policy. Capacity eviction must preserve
active ownership and live page dependencies. Retirement clears all three owners.
Focused tests prove quota refusal, expiry, replay, conflict, cancellation release,
and page/successor publication together. Retained values contain detached proof
and progress, never PSI, K2 sessions, native iterators, or a live project.

### Installed transport and authority qualification

The installed concurrent-read harness separates 156 semantic first attempts from
four peer probes: disconnect, malformed input, admission saturation, and fresh
listener health. Saturation uses the staged configuration catalog and actual
request-read observations, then requires the finite capacity rejection. The
endpoint emits `CONNECTION_RELEASE` after releasing the admitted connection's
permit; the macOS native harness waits on log-change notifications for those
correlated release records before its single health request. Reports retain
bounded stage/outcome duration and byte totals, first attempts, and finite witness
failures without source, responses, descriptors, or connection identities. The
peer checks establish exact rejection shapes and host authority fields; the full
hosted socket-envelope schema matrix remains separate from the exact frame checks.

After the base and concurrency reads, an ordinary edit to the private fixture
passes through native refresh/readiness and a single fresh search must observe
an increased epoch. Both CLI and provider reject an old symbol reference and a
fresh anchor paired with the old upstream continuation, then acquire fresh read
authority. Exact byte restoration repeats readiness and requires another epoch
increase and fresh acquisition. A separate unenrolled owned root refuses issued
references and continuations before selecting an alternate IDE; it does not
qualify two enrolled IDE owners. The receipt retains source digests, epoch
numbers and finite outcomes without tokens or source. The provider's actual outer
completed/document envelope is checked with its qualified same-build output
schema; CLI payloads are checked through the canonical wrapper projection.

The hosted owner identity checks independently increase elapsed-time, work,
result and byte allowances for the exercised output stores. Each change restores
the same detached outcome and reissues the same token. Query checkpoint resume
supplies only the issued token and new grant; the store restores the original
plan. Workspace, published generation, live host lifetime and live epoch changes
reject. Absent and empty execution-budget controls normalize alike; omitted
traversal strategy and explicit breadth-first retain the same semantic choice.
Query `take` steps and query fanout fields are unsupported and reject at the
public wire boundary; traversal bounded fanout remains a supported strategy
whose value participates in continuation identity.

A shared-owner test configures one entry per exercised store, retains four
entries simultaneously, and verifies that changing the epoch retires all four.
This is entry-composition and detached-identity evidence. It does not measure
heap use or replace unchanged-fixture native execution parity for larger grants.

The former Python cross-grant oracle and staged-peer acceptance checks were
retired with the Python native harness. Kotlin query, source, protocol and
hosted-runtime tests own the current production rules for paging, continuation,
schema admission and finite failures. An installed native parity claim requires
a separate runtime observation.

Relation pages retain canonical order within each page. Cross-grant drains
preserve the full occurrence multiset; changing page boundaries does not promise
global fingerprint order. A resumable budget stop retains unmeasured work evidence
until the final complete drain; it never becomes an invented zero omitted count.

Relation observations distinguish confirmed, different and unavailable K2 target
decisions, and found versus unavailable lexical call owners. These finite counts
contain no symbol names, source payloads or live handles. A deferred call owner
is also recorded as `RELATION_CALL_OWNER_UNSUPPORTED`; the returned omission
retains a bounded source occurrence independently of the diagnostic counter.

Source fitting encodes the selected expanded or compact wire projection for each
candidate prefix, including its local selection table and retained-output cursor.
An indivisible source text that prevents any prefix fitting becomes explicitly
withheld with text-byte qualification; source bytes are never truncated.

The `WORKSPACE_REFRESH` control request is dispatched separately from semantic reads over the owned socket. The public `workspace_lifecycle` input no longer selects a refresh effect or task-success rule. The hosted endpoint saves project-owned editor buffers and waits for native incremental recursive VFS refresh before ordinary semantic dispatch and lifecycle opening; failures are finite and observed at that boundary. Explicit file refresh keeps forced dirty marking for external writes. Native lifecycle admission preserves host, project and request identities; pending results remain qualified and failures remain rejections. The [workspace lifecycle owner](../modules/workspace.md) describes its asynchronous effects and internal task rule.

Fresh exact-symbol reads use the request-local acquisition wrapper described in
[query protocol](../modules/query-protocol.md#automatic-acquisition-for-fresh-reads).
Recovery shares the semantic work/time grant and can return refreshed-handle
metadata with qualified results. Graph hop failures retain their distinct scope,
index, compiler and contract causes. Hosted time exhaustion and cancellation
produce separate recovery guidance; neither turns an unvalidated accumulator
into successful evidence.

Application-requested project closure fences the existing project endpoint before native disposal. Already admitted dispatch prevents closure until it leaves; a failed/vetoed close restores admission. Native semantic read admission does not invoke application lifecycle operations. The installed coordinator prepares the workspace before dispatch. For an existing project, application opening waits for any active import and requests one linked model reload on first attach, after tracked Gradle changes, or when the cached model is missing. Subsequent clean openings reuse the admitted model. Its socket client binds the request to the readiness-qualified project descriptor. Project endpoint retirement is observed on that exact service, so a successor project at the same root cannot be mistaken for the retired owner.

Retention telemetry distinguishes the latest accounted bytes and entry count from the maximum accounted byte high-water mark. Gauges observe the existing state owner after allocation, final publication, and cancellation release; they do not sum snapshots or claim a process heap measurement.

Local complete-workload comparison extends the existing semantic reproduction runner
through installed Tool RPC. It pins artifacts and fixture content, reacquires
handles per trial, and drains unchanged grants to terminal completion before
comparing answer evidence. Native receipt schema 6 adds returned discovery and
relation page counts with explicit request-local zeros; missing older counters
remain unavailable. The [runbook](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md#compare-complete-workloads-locally)
keeps counter, byte and overlapping phase-duration units distinct.
