---
type: Kotlin Module Group
title: Semantic read domains
description: Domain contracts refine discovery into exact compiler identity and compose source, relation, traversal, diagnostics, and queries without erasing evidence.
resource: file://query
tags: [kotlin, semantic, query, compiler]
code_sources:
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticReadAttempts.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticAnalysisAttempt.kt
  - path: diagnostic/intellij/src/test/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticNativePhaseTest.kt
  - path: workspace/intellij-read/src/test/kotlin/io/github/amichne/kast/workspace/intellij/read/hosted/HostedDiagnosticNativePhaseTest.kt
  - path: diagnostic/intellij/src/test/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticReadAttemptTest.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundDiagnosticEnumeration.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticResponse.kt
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt
  - path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityAttempt.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceCompilerProjection.kt
  - path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityReadTest.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadCursor.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDetachedRetention.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceStateStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyGraph.kt
    symbols: [sourceDependencyClosure, sourceExpiredEntries]
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourcePublicationSession.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyExpiryTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceClaimExpiryTest.kt
  - path: runtime/hosted/src/test/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceDependencyBindingTest.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadOutcome.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentity.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentityObservation.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDiscoveryKindAdmissionTest.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentityObservationTest.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedNativeDiscoveryCollector.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/ScopedDeclarationDiscoveryTest.kt
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryDiscoveryPlanningTest.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/SymbolDiscoveryProgress.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationSourceAdapter.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationSourceAdmission.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationDiscoveryState.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationPartitionScanner.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationFileScanner.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDiscoveryLeafDeclaration.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationDiscoveryAllowance.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscoveryTest.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryDiscoveryObservation.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijScopedDeclarationEnumeration.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDiscoveryConstraintAdmission.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryServiceSupport.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadObservation.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt
    symbols: [SymbolDiscoveryService]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt
    symbols: [SourceSnapshot]
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationFact.kt
    symbols: [RelationFact]
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt
    symbols: [sourceBoundCallableIdentity]
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPlan.kt
    symbols: [TraversalPlan]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
    symbols: [QueryPlanCompiler, AdmittedQueryPlan]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
    symbols: [QueryService]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadValidationPort]
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
  - path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
    symbols: [SourceDeclarationVisibility]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijSearchScopeCompiler.kt
    symbols: [CompiledIntellijSearchScope, IntellijScopePopulation]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijExactNameIndexes.kt
    symbols: [discoverNative]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryQuery.kt
    symbols: [IntellijNativeDiscoveryQuery]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryAdapter.kt
    symbols: [isAdmittedContributor, isAdmittedContributorName]
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijNativeDiscoveryTest.kt
    symbols: [SymbolDiscoveryTest]
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijSearchScopeSourceRootPolicyTest.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijDiscoveryPackageAdmission.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijSupplementalDiscoveryQuery.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijTextDeclarationDiscoveryQuery.kt
    symbols: [IntellijTextDeclarationDiscoveryQuery]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijTextOccurrenceCollector.kt
    symbols: [collectTextDiscoveryOccurrences]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijTextDeclarationProjector.kt
    symbols: [IntellijTextDeclarationProjector]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIndexedWordContext.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/SymbolTextMatch.kt
    symbols: [SymbolDiscoveryWord, SymbolTextMatch]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationPackageAdmission.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2CallOwnership.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationProjection.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationPlan.kt
    symbols: [IntellijRelationPlanKind]
  - path: relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceEntityTraversalState.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceTraversal.kt
  - path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/SourceCursorReadTest.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/DiagnosticProgressDocument.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticStateContracts.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/DiagnosticPublicationTest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionMeasurements.kt
  - path: workspace/intellij-read/src/main/kotlin/io/github/amichne/kast/workspace/intellij/read/IntellijReadGauge.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticStateRecords.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticRetentionOwnership.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourceRetentionAdmission.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedRetentionOwner.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalContinuation.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationProviderState.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationProviderLocator.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationInventory.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventory.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijDefinitionInventory.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijCalleeInventory.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRetainedRelationRead.kt
  - path: traversal/contract/src/main/kotlin/io/github/amichne/kast/traversal/contract/TraversalPartialExpansion.kt
sources:
  - id: openwiki-source-c5f0a7cc49121c1f25d33773
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RepresentationProvenance.kt
  - id: openwiki-source-6226241beb5c7447a86b663f
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueBoundary.kt
  - id: openwiki-source-d3f0d7e24d35ed4b47aff3d5
    resource: repo://relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueSite.kt
  - id: openwiki-source-eeb2af1b330e389cae564042
    resource: repo://relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijValueFlowNative.kt
  - id: openwiki-source-d16ec0b0b90b8cd04743a4f3
    resource: repo://source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
  - id: openwiki-source-ca4cb79efb8948231564ac6f
    resource: repo://symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolExactService.kt
generated: { by: "codex", at: "2026-10-03T04:45:37.704Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-03T04:45:37.704Z
---

# Semantic read domains

Scoped declaration enumeration preserves every declared source root for ownership
checks, but seeds its frontier only from roots admitted by the captured IDE source
model. Absent conventional Gradle folders cannot become failed partitions;
unavailable partitions inside the admitted universe still qualify coverage.
Directory admission precedes partition capacity accounting, so excluded children
cannot consume eligible capacity. Inventory checks cancellation and elapsed
allowance before preparation and between observations. Finite partition counters
and termination labels explain the native boundary without logging paths.

Symbol discovery returns bounded candidates; exact resolution refines a candidate into compiler identity. Source reads, relation reads, traversal, and diagnostics consume exact, workspace-bound requests rather than re-parsing loose names.

An exact-symbol query can opt into a five-line source window. The query service asks the source port for the symbol's file region within the same admitted read; the source adapter revalidates the exact anchor and committed document before returning text. Returned text and line coordinates retain that source proof through query projection. Failure stays a finite query item cause and incomplete qualification.

Read contracts share `SemanticReadAuthority`, retaining either a published lease
or original-owner live IDE admission. Symbol, relation, and diagnostic services
use an authority-validation port before and after compiler work. Source service
uses an explicit source-context admission port. Installed constructors adapt
published workspace inspection to these ports; live hosts supply their own
current-read validation. The pure owners do not inspect or acquire an IDE.

Discovery constraints survive declaration candidate selection,
exact refinement, relation endpoints, source snapshots, and continuation
fingerprints. Relation and diagnostic facts retain detached `SemanticReadIdentity`.
Published write and topology boundaries continue to require publication evidence.

Native symbol and relation scopes establish path, current model, and file-index
membership without reading PSI packages in `GlobalSearchScope.contains`.
Package admission occurs after provider collection or exact PSI lookup, with
closed admitted, outside-scope, and unsupported outcomes. Discovery, exact
restoration, relation subjects, and relation targets retain this check. Text and
legacy symbol-relation callbacks collect bounded references before projection;
collection exhaustion remains qualified rather than complete.

Scoped indexed-word discovery uses the same `PsiSearchHelper` occurrence path
and compiled scope. Callback capture is bounded and thread-safe; scope and kind
admission precede capture capacity, while package PSI admission follows callbacks.
Projection verifies the word against the current document, identifies the nearest
supported declaration owner, and deduplicates detached owner candidates before
exact refinement. Imports, file-level comments and unsupported nearest owners
retain incomplete-coverage evidence instead of acquiring an outer owner. A
bounded line exemplar is independent of relation facts. Existing counters and
native/projection timings measure admitted capture and projection work; they do
not measure actual AST loads or establish an elapsed-time improvement.

The retained library-inclusive fuzzy and filename contributor paths supply a cached `IdFilter.getProjectIdFilter` before
contributors enumerate keys. The closed scope library policy selects project
content for `EXCLUDE` and project-plus-library IDs for `INCLUDE`. The native key
scan receives this coarse filter before the name cap; it does not prove exact
source-set, directory, package, or file membership.
Collected candidates still pass the compiled scope and retained constraints
before projection. Name, candidate, work, time, and result bounds remain unchanged.

The broad symbol contributor set includes Kotlin classes, functions, properties,
and type aliases. Class-only discovery retains its class contributor. Exact-name
reads continue to use direct index keys. The focused name-filter and scope-policy
run passed 30 adapter checks, including filter forwarding before name capacity
and type-alias contributor admission. The final packaged rerun returned a
qualified minimum of 11 declarations, including the top-level helper and type
alias; exact property lookup completed separately. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the remaining work/discovery limitations. Neither these adapter checks
nor that partial native result establish complete `ALL` coverage or a general
timing improvement.

The symbol scope compiler distinguishes `MODEL_OWNED` from `KNOWN_EMPTY`.
Known-empty discovery requires valid owned roots and readable source/generated
policy roots before exact source-set names are found absent from that owner.
An unknown owner or a policy that excludes the available roots still rejects.
Library inclusion cannot add results to that proven empty source-set scope.

The query domain provides closed typed stages over these operations, including exact-reference fan-in, structured predicates, retained binding-row projection, and typed joins keyed by canonical symbol identity. `QueryPlanCompiler` rejects type-incompatible symbol and binding-row transitions, unknown projected binding names, and output-kind mismatches before execution. `QueryService` then interprets only an admitted plan and retains per-item failures and limitations instead of promoting partial work to completeness.

Visibility filters consume `SourceDeclarationVisibility`, a proof for the exact
selected declaration and snapshot. The internal self read is distinct from the
public child-containment modes; a descendant's visibility cannot satisfy its
parent's predicate. See [semantic query](../flows/semantic-query.md) for the
missing and mismatched evidence outcomes.

## Navigation

- Start in `symbol:contract` for identity and ambiguity.
- Start in `source:contract` for snapshots, coordinates, or visibility.
- Start in `relation:contract` for semantic edge meaning.
- Start in `traversal:contract` for bounded multi-hop state.
- Start in `query:contract` for cross-domain composition.
- Start in [`query:protocol`](query-protocol.md) for shared request admission, reference codecs, and canonical result projection.

Read [semantic query](../flows/semantic-query.md) for execution order and [compiler identity](../glossary/compiler-identity.md) for the key refinement.

Native symbol/relation adapters accept an optional request-local observation capability. Finite counters and termination reasons distinguish name/candidate caps from work/time/result/byte budgets, and unresolved K2 symbols from resolved non-Kotlin PSI or non-Kotlin references. The hosted boundary owns default publication and records effective limits. The [synthetic reproduction report](../../docs/reviews/hosted-semantic-reproduction.md) records the original native causes and the corrected separation of subject selection from workspace expansion.

For reference relations, a K2 identity that differs from the selected subject
proves an indexed candidate is unrelated and is dismissed without an omission.
An unavailable K2 identity still qualifies coverage as unresolved.

Compiler-confirmed reference observations retain the selected target, occurrence
file/range, context, authority and provenance separately from declaration
ownership. A resolved declaration owner can project a real relation fact.
Imports, aliases and file-level annotations remain file-scoped observations;
unavailable ownership carries a finite cause. Neither case fabricates a
declaration owner or a call edge. Native `ReferencesSearch` prepares one bounded
detached inventory over the original admitted search universe. Sorted file,
range and provider locators give the successor a direct candidate position;
restoration does not replay the consumed compiler-confirmation prefix. An
unavailable or unretainable inventory terminates with its exact limitation.
Native locator accounting charges detached UTF-16 text and fixed object overhead
independently of JSON projection estimates. It preserves every descriptor and
scope identity. The native inventory gauge records the prepared state's required
bytes, or the bounded failed attempt including its first unbufferable locator.
These values govern retention admission; they are not measured JVM heap sizes.
The same retained provider state owns detached definitions and callee inventories.
Native definitions retain normalized declaration locators together with the
original scoped provider file. Restoration rechecks that file's scope membership
before compiler-confirming the normalized declaration. Callee discovery retains
exact call sites in the selected declaration. Compiler refinement restores only
the unconsumed suffix. The inventory owns its preparation unit and consumed-item
cursor, so a continuation cannot independently reconstruct cursor progress.
This preserves the original provider universe and compiler emission policy.

Traversal observations bind each partial expansion to its original subject and
depth. They retain known minima and detailed provider, measurement, sample and
remediation evidence. Samples retain at most three distinct located observations.
The owned sample collection proves whether all observed locations remain or a
further distinct location was discarded. Complete samples do not claim that
every omitted input supplied a location. Wire and CLI projections retain this
complete or truncated proof beside the bounded locations. Observed omissions and unvisited expansions survive an
aggregate checkpoint as inherited evidence. An unmeasured resumable budget or
provider boundary describes pending work; exact successor exhaustion can
refine that boundary to complete coverage without discarding a measured omission.

The scoped `ALL` path starts with admitted model-owned source roots and retains a
lexical frontier of unopened VFS directory/file partitions. Each directory is
expanded once into a bounded detached child snapshot; each active Kotlin file
resumes from its next source offset after reacquiring PSI. No global inventory
or consumed PSI prefix is replayed behind the continuation. The remainder binds
scope, target, constraints, authority and provider ordering; its advancing input
revision and discovered/completed file counts distinguish progress from
exhaustion. An unavailable, oversized or unretainable partition produces a
finite blocked cause rather than an unusable successor. Current ALL source
ordering is `kotlin-file-source-v2`, then declaration source offset. ALL and named
declaration observations explicitly declare a Kotlin language universe even
when exhausted or blocked; this does not imply Java declaration discovery. Physical
PSI fixtures exercise native traversal; installed K2 qualification remains a
separate proof boundary. Timings and counts for partition discovery, file
reacquisition, examined leaves and projection survive query composition as
bounded observations. Metadata is charged to returned bytes and checkpoint
retention without consuming semantic row capacity. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.

Search planning applies cheap scope and declaration-family constraints before
expensive work. Project-only fuzzy declarations retain ranked scoped Kotlin-file enumeration;
`ALL` uses the detached partition producer. Mixed declaration families use one
symbol discovery request. Exact
searches invoke only the short-name indexes for requested families. Kind exclusion
precedes package PSI in candidate admission, and scoped enumeration selects kinds
before candidate capacity. File-index callbacks end before package inspection;
excluded class containers remain traversable for eligible members. Qualified
coverage continues through compiler refinement and final projection.

Fuzzy discovery ranks exact, case-insensitive, single-edit, and subsequence name evidence before compiler refinement. A bounded lexical queue retains the best admitted candidates across the selected source files. Exact package selection uses `KotlinExactPackagesIndex`; directory and source-set membership constrain file enumeration before capacity. `DISCOVERY_FILES` bounds lexical file retention independently of semantic work, and lexical retain/replace/drop counters distinguish ranking from compiler refinement. The synthetic 200-module fixture covers deletion and transposition with excluded-directory, package, and test-source noise.

Traversal charges measured one-hop elapsed time rather than the granted time
ceiling. Cumulative page progress proves advancing resumable checkpoints. The
optional bounded-fan-out strategy caps each node's returned edges and moves to
the next frontier while retaining incomplete relation coverage. The default
breadth-first strategy retains unfinished per-node relation pagination.

Elapsed time is an observation, while a read's time allowance is an admitted
grant. If scheduling or provider work overruns that grant, traversal retains the
actual elapsed time and proven facts, stops further reads, and qualifies the
page with `time-limit-reached`. It resumes only when unfinished work remains;
an exhausted overrun is terminal. An over-budget page cannot claim completion.

Exact K2 callable projection distinguishes native callable identity, compiler-owned
enum-entry initializer membership, and finite unavailable ownership stages.
Bounded counters and termination reasons expose that boundary without recording
names, source payloads, references, or live compiler objects. Enum-entry membership
is established only through the compiler containing-symbol chain and equality
with the enum entry's initializer, never from PSI naming.

For a verified enum-entry initializer member, exact projection combines the
compiler enum-entry callable identity and compiler member name. The existing
function/property signature retains that qualified owner identity. This does not
classify the entry itself as a supported class, accept arbitrary anonymous-object
members, or derive authority from source spelling. Unsupported ownership remains
a finite compiler-identity rejection.

The installed enum fixture checks exact, fuzzy and scoped class searches, both
`act` declarations, and reuse of their exact references and signatures through
CLI and provider surfaces. An explicit `distinct_symbols` request verifies canonical
equality of original and restored declarations without a public equality key.
The scoped fixture retains an explicit 32-work-unit
grant to qualify enum exclusion before candidate capacity. This does not establish
the separate source-enumeration compiler-work ordering gate.

Native source enumeration allocates a fresh detached collector for each read
attempt while keeping the request's execution accounting. A cancelled attempt
cannot publish its partially collected page. Requested declaration kinds are
checked before visibility resolution and candidate construction; structural
selectors, parents, ranges and depths still preserve eligible descendants inside
excluded containers. The focused excluded-input and cancellation tests prove
these boundaries without claiming installed IDE qualification.

Source cursors carry a detached snapshot, selected region, semantic request,
emitted-entity ordinal, and advancing structural traversal revision. Pending
work retains source ranges, provider descriptors, structural parents and depths,
same-node parameter phases, and already-proven lookahead entities. Native
restoration resumes these locators directly; it does not traverse and refine the
consumed entity prefix. A successor may keep its emitted ordinal when bounded
work advances through excluded containers before another eligible entity. The hosted source state store owns these cursors and
encoded output suffixes under one publication session. It checks authority and
request identity before native reads; only final admitted publication exposes
children. Rejected or cancelled publication revokes its own allocations, and
published pages replay their cached result without re-entering the provider.
Byte, entry and original token-age bounds apply to the shared owner. Admission,
deduplication and publication require the complete dependency closure to remain
live under the same semantic request and authority. A younger output page cannot
extend an older cursor's deadline, and a running cursor cannot become another
attempt's deduplicated successor. One-shot source projections use the cursorless
port and cannot advertise retained cursor capacity.

Source continuation admission preserves finite causes before invoking the provider:
missing, expired, evicted, or retired tokens yield `CONTINUATION_UNAVAILABLE`;
a changed context yields `SOURCE_SNAPSHOT_MISMATCH`; a changed request yields
`CONTINUATION_REQUEST_MISMATCH`. Provider contract failures remain distinct.

Kotlin caller admission recognizes native implicit `invoke` references, whose
PSI element spans the entire call rather than its callee name. Both implicit
and explicit call shapes still require K2 confirmation of the selected target.
Constructor caller admission retains its separate callee-range and constructor-owner proof.

Kotlin one-hop calls use an explicit lexical ownership boundary. Calls in local
property initializers belong to the enclosing callable. A named nested function
keeps its own owner; callers do not climb past it. A K2-resolved function without
a global callable ID receives a source-bound identity from its exact file and
declaration position plus its compiler signature. Both directions refine a
literal lambda boundary only when a successful K2 call maps that argument to an
ordinary function parameter of the selected inline callable. Each intervening boundary must pass;
returned/stored lambdas, non-inline callbacks, `noinline`, and `crossinline`
are excluded as proven non-caller boundaries. Accessors remain unsupported.
Unresolved/ambiguous argument mappings retain `UNRESOLVED_TARGET`. The admitted
named owner survives until endpoint projection; the inner occurrence,
independent target resolution, scope and lifetime remain unchanged. Owner
counters distinguish admitted, excluded, and unavailable boundaries. Kotlin
reference-shape counters distinguish admitted sites from sites skipped by the
selected relation shape before K2 target confirmation. Omission
samples remain for unavailable ownership. Reference and type-use ownership is
unchanged.
PSI tests prove lexical boundaries only; the native call oracle checks compiler
ownership, forward/inverse occurrence parity, independent omissions and pagination.
Exact static relations make no claim about runtime execution.

Qualified exact edges continue through query stages and the traversal frontier.
Recording-reader regressions distinguish downstream reads from emitted edge
depth: reading a leaf increases `totalReads` without increasing
`maximumDepthReached`. Terminal omissions survive parent pagination; draining a
recoverable child result limit does not manufacture permanent incompleteness.
Diagnostic scan progress is separate from `DiagnosticScope` and complete compiler
coverage. The public scanner advances a detached indexed-file set, analyzes
one complete file per unit, and drains that file's diagnostic suffix without
repeating analysis. Enumeration, analysis, and output advance in the same call
while the original work, elapsed-time, and result grants remain. Exhausted
enumeration reports its consumed callback work, including replayed identities
and interrupted attempts; analysis spends one remaining unit per complete file.
Every stage validates the original authority before further work and publication. An empty intermediate page cannot establish absence, and an
indivisible compiler unit that overruns its time grant rejects explicitly.
The complete-scope diagnostic and mutation verification adapters retain their
existing contracts. The public request carries an opaque continuation and an independent
execution budget. Qualified replies distinguish enumeration, analysis and retained
output stops. The inventory has no numeric total until enumeration exhausts.
Encoded output suffixes retain the original scan continuation, coverage and order.

The directory adapter streams the existing Kotlin file-type index through
`processValues`, after source-domain and path constraints. It retains only a
bounded set of identities encountered so far. The complete sorted inventory is
established after enumeration exhausts; index iteration order is never treated
as stable. Replayed callbacks consume work. A grant that cannot reach a new
identity rejects with an increase-grant outcome, and retention saturation rejects
explicitly. Neither outcome publishes an unchanged continuation. The adapter
avoids `getContainingFilesIterator`, whose pinned implementation builds a full
file-ID set before returning its lazily reified virtual files.

Diagnostic scan checkpoints, encoded output suffixes, replay pages and active
claims share one owner bounded by `QUERY_CONTINUATION_ENTRIES` and
`QUERY_CONTINUATION_BYTES` (defaults: 64 entries and 32 MiB). Each scan checkpoint
also obeys `QUERY_CHECKPOINT_BYTES` (default 8 MiB). Identity text, pending file
sets, diagnostics, coverage, replay keys and object overhead are charged before
publication. Children remain hidden until the exact fitted page commits after
final host validation. Replay preserves the same successor without renewing age;
project or epoch retirement clears the owner. Scan-checkpoint retention refusal
keeps established diagnostic facts, inventory, analyzed files and known count,
with `retention_capacity_exceeded` and no unusable native successor. Output
retention refusal similarly publishes a fitting proven prefix with the closed
`retentionFailure` cause. A retained output page can still drain established
facts while carrying an original scan retention failure.

Pinned SDK 262.9437.185 `processValuesInScope` returns immediately when its
processor returns false. The adapter supplies `IdFilter.ACCEPT_ALL` and its own
source/path scope; `getProjectIdFilter(project, false)` is deliberately avoided
because a cache miss builds a complete content-file bit set. The pinned registry
default `indexing.filetype.over.vfs=false` selects the index implementation;
its alternate VFS mode is rejected without changing registry state. Native IDs
excluded by `scope.contains` do not enter the callback work counter. Scope checks
poll cancellation and the service checks elapsed time before publication, but
this does not prove a per-ID work bound for a long excluded-only native prefix.
That strict native internal-work qualification remains separate from the compiled
callback, replay, exclusion-capacity and elapsed-rejection tests.

Diagnostic resumes preserve the original path, authority and `max_diagnostics` semantic choice. Execution grants may change. Checkpoint admission charges the retained query and authority identity against both the per-checkpoint and aggregate retention bounds. Admitted rejections preserve the actual execution grant report and a finite recovery direction; that direction authorizes no setting change or epoch migration.

Diagnostic response replay keys retain normalized caller grant selections,
including configured-default selections, rather than an invocation's fluctuating
host elapsed-time capacity. The same caller request and basis therefore retain
identical semantic pages and continuation tokens when the host deadline clamp
changes. A changed caller grant shapes a distinct execution. Every invocation
still publishes its own requested/effective report; replay does not reuse an old
report as current admission. Installed replay checks compare all semantic fields,
including continuation and evidence, while validating each actual report separately.

The compiler and enumeration `readAction` closures invoke concrete diagnostic
attempt functions that create fresh collectors inside each attempt. Interrupted
attempts publish neither facts nor inventory; enumeration retains the request's
consumed-work allowance across re-entry. Deterministic tests invoke these same
production blocks with pinned-SDK `ProcessCanceledException` and coroutine
cancellation after accepted facts or identities, then verify a clean re-entry and
unchanged cancellation identity. This is simulated scheduling over production
attempt code, not evidence of native IDE preemption or an executed K2 session.
The same request-local observation capability records diagnostic scope entry
before native admission, enumeration entry before guarded index work, and
analysis entry immediately before K2 analysis and detached projection. The
hosted receipt preserves bounded first-entry and cumulative duration evidence
after success, rejection or cancellation drainage. It records no source text,
diagnostic payload or retained compiler object.

An evicted diagnostic continuation remains unavailable. A cached first page with
a missing dependency rejects rather than silently starting another scan. Live
published pages pin their transitive dependencies against capacity eviction
under the same quota. All query, source and diagnostic owners still enforce each
token's original deadline. Active claims preserve physical storage until release,
without permitting fresh admission or publication after expiry. A younger page's
validity ends at the oldest referenced dependency's deadline. The source owner
also prunes expired or broken pages outside active claims. Expiry, capacity, retirement and semantic request
checks retain their distinct finite meaning. Source and diagnostic owner gauges
report current retained bytes and entry counts, with an independent maximum byte
high-water mark. These values estimate detached quota accounting, not JVM heap.

Internal exact symbol resolution rejects invalid PSI as stale and a matching declaration without a source range as unsupported. Unexpected native failures remain distinct from genuine index unavailability through query and wire projection; the existing native diagnostic receipt retains the correlated failure stage and class. Neither rejection becomes a complete empty result.

Definition expansion normalizes Kotlin origin, navigation and light-element
representations before declaration descriptors and K2 confirmation. Equivalent
supported representations share a canonical provider identity; Java members
remain eligible for the existing proof. Unsupported wrappers remain omissions,
and platform cancellation propagates. This normalization does not deduplicate
occurrence pipelines or replace their explicit distinct stage.

Native read diagnostics retain returned discovery/relation page counters in
addition to existing work counters and phase observations. Each hosted request
initializes both page counters to an observed zero; only a returned native page
increments its counter. These measurements stay outside model-facing output and
do not equate page quantity with CPU cost or complete semantic coverage.

## Exact value sites and reviewed models

`ValueInvocation` retains the enclosing compiler declaration, exact invocation range, callable compiler identity and semantic basis. Its result site is distinct from another call of the same function. Value sites add a closed expression, local binding/read, formal argument, return or property-assignment role; an argument retains the actual invocation and resolved formal position.

The current forward adapter handles bounded local immutable bindings and reads, immediate arguments, direct transparent wrapper returns and supported value-producing branch alternatives. Mutable control flow, unsupported expressions, unresolved references, external calls and exhausted grants remain named obligations. All PSI and K2 observations stay inside the native read boundary; retained sites and transfers are detached data.

Reviewed representation rules establish origins, transfers, transformations and consumer expectations. Current state and history are separate: a transformation replaces current representation while retaining its prior evidence. Boundary models bind exact proven source/target positions, contract identity/version and supplied compatibility assumptions. A modeled terminal differs from an unresolved boundary; persistence retains retention/decoding/migration obligations. Compiler identity and flow do not establish runtime encryption or deployment compatibility.
