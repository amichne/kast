---
type: Kotlin Module Group
title: Semantic read domains
description: Domain contracts refine discovery into exact compiler identity and compose source, relation, traversal, diagnostics, and queries without erasing evidence.
resource: file://query
tags: [kotlin, semantic, query, compiler]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticReadAttempts.kt
  - path: diagnostic/intellij/src/test/kotlin/io/github/amichne/kast/diagnostic/intellij/DiagnosticReadAttemptTest.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/ProjectBoundDiagnosticEnumeration.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/DiagnosticCheckpointStore.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedDiagnosticResponse.kt
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticScanService.kt
  - path: diagnostic/intellij/src/main/kotlin/io/github/amichne/kast/diagnostic/intellij/BoundedDiagnosticEnumeration.kt
  - path: diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticScan.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityAttempt.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityPageCollector.kt
  - path: source/intellij/src/test/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceEntityReadTest.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadContinuations.kt
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/IntellijSourceReadPort.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadOutcome.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentity.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentityObservation.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDiscoveryKindAdmissionTest.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/IntellijCallableIdentityObservationTest.kt
  - path: packaging/hosted_enum_read_regression.py
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/BoundedNativeDiscoveryCollector.kt
  - path: symbol/intellij/src/test/kotlin/io/github/amichne/kast/symbol/intellij/ScopedDeclarationDiscoveryTest.kt
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryDiscoveryPlanningTest.kt
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
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijPsiExactDeclarationLookup.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationScopeCompiler.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRelationPackageAdmission.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2CallOwnership.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationProjection.kt
  - path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt
  - path: relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt
  - path: diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
---

# Semantic read domains

Symbol discovery returns bounded candidates; exact resolution refines a candidate into compiler identity. Source reads, relation reads, traversal, and diagnostics consume exact, workspace-bound requests rather than re-parsing loose names.

Read contracts share `SemanticReadAuthority`, retaining either a published lease
or original-owner live IDE admission. Symbol, relation, and diagnostic services
use an authority-validation port before and after compiler work. Source service
uses an explicit source-context admission port. Installed constructors adapt
published workspace inspection to these ports; live hosts supply their own
current-read validation. The pure owners do not inspect or acquire an IDE.

Discovery constraints survive declaration, file, and text candidate selection,
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

The query domain provides a closed linear algebra over these operations. `QueryPlanCompiler` rejects type-incompatible stage transitions before execution. `QueryService` then interprets only an admitted plan and retains per-item failures and limitations instead of promoting partial work to completeness.

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

The corrected scoped `ALL` path enumerates Kotlin declarations through the admitted file-type index without workspace name enumeration. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.

Search planning applies cheap scope and declaration-family constraints before
expensive work. Project-only fuzzy declarations share scoped Kotlin-file enumeration
with `ALL`; mixed declaration families use one symbol discovery request. Exact
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

Source continuation admission preserves finite causes before invoking the provider:
missing, expired, evicted, or retired tokens yield `CONTINUATION_UNAVAILABLE`;
a changed context yields `SOURCE_SNAPSHOT_MISMATCH`; a changed request yields
`CONTINUATION_REQUEST_MISMATCH`. Provider contract failures remain distinct.

Kotlin one-hop calls use an explicit lexical ownership boundary. Calls in local
property initializers belong to the enclosing callable. A named nested function
keeps its own owner; callers do not climb past it. A K2-resolved function without
a global callable ID receives a source-bound identity from its exact file and
declaration position plus its compiler signature. Both directions refine a
literal lambda boundary only when a successful K2 call maps that argument to an
ordinary function parameter of the selected inline callable. Each intervening boundary must pass;
returned/stored lambdas, non-inline callbacks, `noinline`, `crossinline`, and
accessors remain unsupported. Unresolved/ambiguous argument mappings retain
`UNRESOLVED_TARGET`. The admitted named owner survives until endpoint projection;
the inner occurrence, independent target resolution, scope and lifetime remain
unchanged. Existing finite owner counters and omission samples describe both
admitted and unavailable ownership. Reference and type-use ownership is unchanged.
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

Diagnostic checkpoints and replay payloads share one pool bounded by
`QUERY_CONTINUATION_ENTRIES` and `QUERY_CONTINUATION_BYTES`. Diagnostic encoded
output uses the existing separate output pool with the same limits. The diagnostic
aggregate therefore permits twice each configured pool limit (defaults: 128
entries and 64 MiB), independently of other read owners. Each scan checkpoint
also obeys `QUERY_CHECKPOINT_BYTES` (default 8 MiB). Identity text, pending file
sets, diagnostics, coverage, replay keys and object overhead are charged before
publication. The scan pool inherits the original creation time; output entries
follow the existing per-entry expiry policy. Project/epoch disposal clears both.

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

An evicted diagnostic continuation remains unavailable. If its first-page replay
survives without the required checkpoint, a tokenless request may discard that
orphaned replay and start a fresh bounded scan. The same retained-child check
applies during publication, including interleaved admissions. Resume requests do
not restart implicitly, and expiry, capacity and semantic request checks retain
their existing authority.

Exact symbol lookup rejects invalid PSI as stale and a matching declaration without a source range as unsupported. Unexpected native failures remain distinct from genuine index unavailability through symbol inspection and wire projection; the existing native diagnostic receipt retains the correlated failure stage and class. Neither rejection becomes a complete empty result.
