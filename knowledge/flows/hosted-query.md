---
type: Runtime Flow
title: Existing-IDE semantic query
description: An existing IDEA project owns five canonical read operations, with bounded live authority and scoped native CLI/provider acceptance.
resource: file://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted
tags: [intellij, kotlin, semantic-query, lifecycle]
timestamp: 2026-09-25T00:00:00Z
code_sources:
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/lifecycle/HostedProjectAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedGradleChangeTracker.kt
  - path: packaging/hosted_wire_schema.py
  - path: packaging/hosted_peer_probe.py
  - path: packaging/hosted_concurrent_read.py
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedPublicationDeadlineTest.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadPublicationAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadFailureReports.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedContainmentReportTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedContainmentBudgetTest.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/ide/HostedReportAdmissionTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadAllowanceIdentityTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedContinuationOwnerRetentionTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryUnsupportedIdentityTest.kt
  - path: packaging/hosted_resume_budget_regression.py
  - path: packaging/test-hosted-resume-budget-regression.py
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/QueryCheckpointReplayTest.kt
  - path: packaging/hosted_authority_read_regression.py
  - path: packaging/hosted_read_transport.py
  - path: app-server/src/test/kotlin/io/github/amichne/kast/appserver/acceptance/hostedchange/NativeReadRequest.kt
  - path: packaging/hosted_transport_observation.py
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
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/NativeSourceSelections.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceProtocolBudget.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadContinuations.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ExecutionBudgetDocument.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedExecutionBudgetRequest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReadBudgetAdmission.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnectionAdmissionTest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedOutputPages.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalSource.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceOutputPages.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDeadline.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
  - path: kernel/src/main/kotlin/io/github/amichne/kast/kernel/ReadLimits.kt
  - path: docs/hosted-read-configuration.md
  - path: experiments/host-observation/reproduce_semantic_queries.py
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedReadDiagnostics.kt
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
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalDiscoveryAdmission.kt
    symbols: [admitDiscoveryRequest, specialistDiscoveryWorkspaceScope]
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
  - path: packaging/run-gradle-source-scope-acceptance.py
  - path: experiments/host-observation/gradle-source-scope-fixture.kts.template
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
ownership still rejects. Selected-build unavailable folders, unsupported kinds,
missing cached owners, and classification mismatches still reject with bounded
module/root evidence. The hosted failure DTO and diagnostic outcome preserve that
evidence, including explicit truncation and both sides of classification mismatches.
Separate selected/foreign module counters describe successful admission decisions.

Missing or inconsistent ownership rejects before name filtering. Most-specific roots win,
including generated, excluded and resource roots; an unknown nested source-folder
kind rejects rather than inheriting an allowed parent. This gate performs no import
or sync.

The native Gradle ownership fixture imports a composite project in a private IDEA
profile, finds `NativeChangeTarget` through exact `symbol.discover`, then adds an
unavailable source folder to the included build and repeats the successful search.
It adds the equivalent folder to the selected build and requires a rejection with
module/root identity that validates against the hosted schema. Run it after
`:runtime:hosted:hostedPlugin`, with a new report path:

```sh
uv run --with jsonschema==4.26.0 python packaging/run-gradle-source-scope-acceptance.py \
  --idea-home /path/to/pinned/idea/home \
  --plugin /path/to/hosted-plugin.zip \
  --report build/reports/gradle-source-scope/native.json
```

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

`HostedCanonicalQuery` composes five public canonical reads: `query.run`,
`symbol.discover`, `symbol.inspect`, `source.read`, and `diagnostic.check`.
Query expansion supplies one-hop relation facts through occurrence output;
query walk composes the traversal domain operation and projects depth-bearing
records and coverage. The host supplies pure services, project-bound
ports, current-model reference restoration, and explicit budgets to the reusable
[`query:protocol`](../modules/query-protocol.md) boundary. Query evaluation keeps
one request budget across its stages; specialist request limits intersect bounded
host caps. Evaluation and wire projection share the admitted read lifetime and
the executor's whole-request deadline. Output carries live evidence without a
workspace generation.

Specialist `symbol.discover` name and workspace-text requests under live authority
use authored production and test roots from the admitted project, excluding
generated sources and libraries. Published authority retains its existing
library-inclusive scope policy. This explicit distinction keeps supported live
requests executable without claiming library-read parity; wider native library
coverage remains a separate qualification boundary.

`HostedConnectionAdmission` admits up to `HOST_CONNECTIONS` frame exchanges
(default 16); the native accept backlog is `HOST_ACCEPT_BACKLOG` (default 64).
One further connection may receive a bounded capacity rejection without semantic
admission. A shared mutex retains serialization of all semantic and mutation
operations. Queue admission reserves the configured host-query allowance and
100 ms of connection time for publication; insufficient time returns
`ADMISSION_DEADLINE_EXCEEDED` before dispatch. A blocked request frame therefore
occupies one connection slot without blocking other frame reads.

This means parallel callers can overlap framing and admission but one project endpoint still executes semantic requests one at a time. `HostedQueryLifetime` also admits only one invocation; removing the transport mutex alone would turn overlap into `BUSY` rejection. The connected provider and App Server are persistent JVM processes; ordinary hosted calls use a direct Unix socket exchange and do not start a JVM per request. See the [throughput evaluation](../../docs/reviews/semantic-read-throughput.md) for the measured transport-only baseline and the semantic concurrency limit.

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
recorded PID to be absent, the descriptor to match this root and current protocol,
and both protected artifacts to retain their admitted physical identities. Live
or reused PIDs, malformed descriptors, symlinks and unpaired artifacts fail closed.
Admission and retirement emit bounded stage/outcome observations.

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

The opt-in [semantic reproduction runner](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md) separates fixture creation/import, runtime pinning, and read-only public CLI/provider replay. The hosted service publishes one bounded native diagnostic receipt by default after a request drains, including effective configuration/provenance, stage durations, remaining outer deadline, contributor counts and precise termination reasons. Diagnostics carry only finite categories and bounded counts; they do not alter budgets or strengthen qualified coverage. An epoch change invalidates cross-request reference evidence.

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

The plugin-only runtime retirement removes isolated composition/import from the active build and topology publication from the shipped runtime graph. Topology modules remain buildable for upcoming graph work. Earlier acceptance records above remain historical. The shared `HostedSemanticServices` factory now supplies canonical reads, planning and verification inside each admitted read context. Diagnostic schema 4 distinguishes transaction evaluation from complete, qualified and rejected semantic results, retaining bounded stage and termination evidence.

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
and retry once at hosted dispatch. A canonical read whose final freshness check
observes a moved VFS epoch discards that result and repeats within the same host
deadline. Other post-evaluation rejections and change workflows are not replayed.

Typed request decoding rejects a supplied returned-byte allowance below the wire
owner's serialized schema/operation identity size before semantic dispatch. This is
a necessary lower bound, not an exact sufficient envelope size. The original
encoder still fits bodies, reports and continuations against the admitted allowance.

`HostedQueryContinuations` owns one `QueryStateStore` for detached query execution
checkpoints and immutable retained results under the same entry, byte and lifetime
limits. Transport output suffixes remain separate. Epoch replacement and project
disposal clear the state. Resume takes only an issued continuation plus an optional
new grant; the stored checkpoint supplies the admitted plan and pending tasks.
Restored results require the same semantic basis, while read-result uses its own
presentation cursor and no semantic provider call. Host admission precedes each
lookup. No retained entry holds PSI, K2 symbols or an IDE observer callback. The
serialized output budget is checked after compact exact references and selected
projection fields are encoded.
When a result is retained, the store issues row IDs scoped to that result and the
query projection returns them with its rows. A composed run can select issued
rows without turning their IDs into exact-symbol references.

Transport output cursors are replayable until expiry or eviction. Equal retained requests
and outcomes have equal child identities; replay does not refresh expiry. The
request identity retains the semantic request while excluding caller execution
controls. Changed authority or semantic request is rejected. Each of the
query state, query output, source output, traversal output,
diagnostic output and diagnostic checkpoint stores has the configured entry/byte
bound. No retained entry contains PSI or K2 state.

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
renewing its expiry or consuming another entry. Restoration is non-consuming.
Query checkpoints and immutable results share one quota but retain distinct typed
references. Query pipeline checkpoints and output suffixes exclude caller execution
controls from semantic identity. Query page result limits also constrain retained
output; every page preserves known item failures. A query `take` stage is not
admitted. Source and traversal caller controls retain the same admitted report;
traversal encoded fitting remains unfinished.

Source entity cursors retain typed token keys and exact snapshot, region, and
selection identity. Their binding excludes entity/text page allowances; an equal
source cursor position reuses its token. The source continuation owner is separate
from native entity collection.

The source continuation owner applies independent entry, charged-byte, and age bounds. It expires entries before admission and issuance, and replay does not renew token age. Retention includes detached snapshot/scope identity but no source payload. Oversized entries fail before issuance; retirement clears every entry.

Source requests require an explicit resource grant. Hosted source admission retains the same immutable resource object and intersects entity/text projection limits. Query visibility predicates transfer one charged unit and the remaining elapsed allowance into their exact SELF source read; failed admission retains the unstarted pipeline task. Native source enumeration now charges visited PSI units against that grant and checks monotonic elapsed time before each unit and at completion. Accounting survives canceled read attempts, while their PSI and detached result buffers do not. Source encoding now fits the complete encoded envelope, including report and cursor, before publishing a nonempty entity prefix. Its `source-output:v1` cursor retains the detached suffix and original upstream qualification under the shared hosted `QUERY_CONTINUATION_*` policy, independently of the native `SOURCE_CONTINUATION_*` policy. Source output identity retains anchor, region, entity selection, and text projection but excludes entity/text page allowances and execution controls. Every page retains requested text; an indivisible item or mandatory envelope that cannot fit is rejected without an empty unchanged cursor.

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

Native source enumeration asks the ordered page owner to admit each known declaration kind before deferred visibility and candidate projection. Excluded containers retain their structural selectors and traversal, and primary-constructor properties use the same kind admission. Native source enumeration feeds the existing ordered page owner incrementally. It retains only the selected entity page and one ordering/lookahead witness, discards excluded entities and previously delivered ordinals, and stops provider work at eligible lookahead. The production `IntellijSourceEntityAttempt.collect` boundary creates a fresh collector inside each read-action invocation; only the request execution meter survives. Controlled cancellation tests prove that a canceled invocation publishes no page, its facts do not enter the next invocation, and neither work nor elapsed allowance resets. Native IntelliJ retry scheduling remains separate installed evidence. Sequence-based fixtures use the same filter, ordering, and page owner, with their explicit fixed stream guard. Native collection uses the admitted caller work/time grant and does not reconstruct that guard.

Decoded execution reports also retain their dimension rules: elapsed limits admit deadline clamps; result and byte limits admit transport clamps; work limits admit only the operator ceiling. Every result amount remains within the integer domain. Invalid dimension evidence is rejected by the report decoder before a report value is exposed.

Query qualification owns mandatory closed execution progress: resumable with an upstream checkpoint or retained-output checkpoint, or terminal-incomplete with a finite reason. A retained-output checkpoint reports the original upstream coverage, preserving terminal reasons without asserting that an interrupted scan can resume. Empty upstream pages explicitly require increased execution allowances. `QueryRunResult` separately reports retention outcome and an optional result presentation cursor. That cursor pages immutable retained rows; it is not an execution continuation. CLI compatibility fields for execution progress are derived from qualification. Wire decoding rejects missing progress and noncanonical checkpoint families.

Source qualifications own closed resumable or terminal-incomplete progress. Native
source checkpoints and hosted retained-output checkpoints are separate variants;
retained output preserves original complete, resumable, or terminal coverage.
Legacy cursor availability is derived from that authority. Empty native pages
require an increased execution allowance. A terminal text-withheld explanation
requires a matching text-byte limitation; other upstream gaps remain finite
terminal evidence. Source wire admission rejects missing progress, unsupported
variants and mismatched checkpoint families.

### Hosted continuation retention bounds

Each `HostedQueryContinuations.Active` owns six independently bounded stores:
one query state store sharing its capacity between execution checkpoints and
immutable results, four output-suffix stores for query, source,
traversal and diagnostic reads, and one diagnostic checkpoint store. For
configured entry bound `C` and charged-byte bound `B`, these owners have an
aggregate upper bound of `6 × C` entries and `6 × B` charged bytes. `B` is an
accounting bound, not measured JVM heap use: output stores charge four times
encoded request plus outcome bytes; query state charges each detached payload
plus four times its normalized run request bytes. The separate native source
continuation owner and compact reference store have their own policies.

The native source continuation owner expires at age greater than or equal to
its TTL and evicts the least recently accessed checkpoint; a successful replay
updates eviction order without renewing creation time. Its exact-boundary and
access-order policies are distinct from the hosted stores. Hosted output and
query-state entries expire when age is strictly greater than TTL. An entry
remains available at exactly TTL; restore and identical reissuance do not renew
its creation time. Capacity eviction removes the oldest inserted entry even if
it was replayed. Owner retirement clears all six stores. Focused tests cover
TTL−1, exact TTL, TTL+1, replay, eviction and clear for the relevant store
owners; the four-store shared-owner test exercises query state plus query,
source and traversal suffixes, without claiming six-store aggregate
measurement. Retained values are detached identities and results; these stores
do not retain PSI, K2 sessions or a live project.

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

The installed resume-budget helper defines twelve bounded cases per surface:
query symbols, source and query occurrences, each with independently larger elapsed-time,
work, result and byte allowances. Complete query and source drains retain exact
record order, source child ranges, snapshots and saved text. Query occurrence drains retain
the multiset of full occurrences and compiler evidence, including duplicates, plus
canonical order within each returned page. Native cursor order and page-local
relation sorting can change global concatenation order when grants change page
boundaries; this does not relax occurrence identity or per-page order. Issued upstream
and retained-output checkpoints keep their distinct request positions.
Each drain admits at most sixteen pages and one thousand
records, rejects repeated tokens or changed authority/grants, and records only
finite assertion names and counts. A complete low-grant page requires no invented
continuation; time/work cases do not claim a deterministic wall-clock cutoff.
A query occurrence page stopped by a result, byte, time or work limit retains an
`unmeasured_on_page` omission, matching limitation, available checkpoint, empty
samples and `INCREASE_READ_LIMIT` remediation. Permanent omissions remain equal
to the baseline; the final drain restores its final omissions. An unmeasured
page remainder does not assert observed missing occurrences in the final drain.

Traversal cross-grant drains likewise compare full occurrence and proof multisets,
final edge/depth progress and terminal partial expansions. Retained suffix replay
separately preserves ordered records and restores its upstream coverage.
Local Python checks qualify this orchestration and comparison logic. Native
parity requires invoking the helper through the integrated staged artifact;
its presence alone is not an installed-product qualification result.

Native malformed, saturated, and health peer responses are validated in memory
against the hosted endpoint schema extracted from the exact staged product jar.
Peer receipts retain the schema digest only after validation. Missing, duplicate,
or invalid embedded schemas fail closed; disconnected peers do not claim a reply.
This endpoint evidence is separate from canonical tool-document and actual
provider-envelope validation.

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

The `WORKSPACE_REFRESH` control request is dispatched separately from semantic reads and remains available to the disposable native acceptance fixture over the owned socket. The public `workspace_lifecycle` input no longer selects a refresh effect or task-success rule. The hosted endpoint saves project-owned editor buffers and waits for native incremental recursive VFS refresh before ordinary semantic dispatch and lifecycle opening; failures are finite and observed at that boundary. Explicit file refresh keeps forced dirty marking for external writes. Native lifecycle admission preserves host, project and request identities; pending results remain qualified and failures remain rejections. The [workspace lifecycle owner](../modules/workspace.md) describes its asynchronous effects and internal task rule.

Fresh exact-symbol reads use the request-local acquisition wrapper described in
[query protocol](../modules/query-protocol.md#automatic-acquisition-for-fresh-reads).
Recovery shares the semantic work/time grant and can return refreshed-handle
metadata with qualified results. Graph hop failures retain their distinct scope,
index, compiler and contract causes. Hosted time exhaustion and cancellation
produce separate recovery guidance; neither turns an unvalidated accumulator
into successful evidence.

Application-requested project closure fences the existing project endpoint before native disposal. Already admitted dispatch prevents closure until it leaves; a failed/vetoed close restores admission. Native semantic read admission does not invoke application lifecycle operations. The installed coordinator prepares the workspace before dispatch. For an existing project, application opening waits for any active import and requests one linked model reload on first attach, after tracked Gradle changes, or when the cached model is missing. Subsequent clean openings reuse the admitted model. Its socket client binds the request to the readiness-qualified project descriptor. Project endpoint retirement is observed on that exact service, so a successor project at the same root cannot be mistaken for the retired owner.
