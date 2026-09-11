---
type: Runtime Flow
title: Existing-IDE semantic query
description: An existing IDEA project owns the default seven canonical reads, with bounded live authority and scoped native CLI/provider acceptance.
resource: file://workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted
tags: [intellij, kotlin, semantic-query, lifecycle]
timestamp: 2026-09-11T00:00:00Z
code_sources:
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
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeQuery.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeSocketClient.kt
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
  - path: experiments/host-observation/run_hosted_query.py
  - path: experiments/host-observation/hosted-query.kts.template
  - path: experiments/host-observation/hosted-plugin-unload.kts.template
  - path: experiments/host-observation/hosted-project-restoration.kts.template
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-query.schema.json
  - path: protocol/contract/src/main/resources/ide-hosted/hosted-endpoint.schema.json
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedConnection.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint.kt
  - path: experiments/host-observation/kast_ide.py
  - path: experiments/host-observation/qualify_hosted_index.py
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedChangeApply.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedPreWriteObservation.kt
---

# Existing-IDE semantic query

## Default canonical read path

`selectCliRuntimePath` selects the semantic command families before installed
bootstrap in `KastCliMain`, including the optional leading `--` command
delimiter. The default path sends all seven canonical reads to
the existing project endpoint; an absent host rejects without opening a workspace,
importing Gradle, or starting an isolated worker.

The ordinary-query scope gate has a separate project-bound capture for exact
imported Gradle names. It reads `ExternalProjectDataCache` and joins explicit
`ExternalSourceSet.name` facts with the current IDE source folders. Missing or
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

`HostedCanonicalQuery` composes all seven public canonical reads: `query.run`,
`symbol.discover`, `symbol.inspect`, `source.read`, `relation.read`,
`traversal.run`, and `diagnostic.check`. It supplies pure services, project-bound
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
the stronger production [workspace publication](workspace-publication.md).

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
authority across requests. The primary native `kast index classes` command and Python
acceptance client reject missing hosts without opening an isolated workspace.
Incremental creation, class renaming, and deletion were qualified against the
same original IDE index. Broader semantic CLI/App Server routing and stronger
workspace publication remain separate integration boundaries.

`kast index supertype` uses the qualified selector. Both public indexing reads
run before isolated bootstrap, require no Python, and leave index maintenance
to IDEA. The earlier `kast ide` spelling shares the same implementation.
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

## Corrected native discovery and relation expansion

The subsequent [semantic reproduction review](../../docs/reviews/hosted-semantic-reproduction.md) records complete fixture `ALL` queries under unchanged default budgets. Scoped Kotlin file indexes supply declarations directly; exact names retain their direct indexes and fuzzy discovery retains bounded contributors. Constructor properties refine through their generated K2 property symbol. Query `EXPAND` retains the original selected subject while admitting related endpoints in the workspace search boundary. Java references resolve through K2 identity, and explicitly excluded library calls do not make project-only callee coverage incomplete. The earlier acceptance limitations above remain historical evidence.

The read policy is immutable per host service and rejects invalid settings. CLI/provider transport capacities use the same typed parameter catalogue. See [read configuration](../../docs/hosted-read-configuration.md).


The new `tool` CLI family lowers the five [public intent tools](../contracts/public-tools.md) into the existing canonical operations before taking this same existing-IDE path. Native semantics and reference authority remain here; tool syntax is not compiler evidence.
