---
type: Kotlin Module
title: Query protocol
description: Shared semantic-read admission and projection bind canonical requests and detached references to an authority supplied by the owning host.
resource: file://query/protocol
tags: [kotlin, protocol, query, authority]
code_sources:
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryTextMatchProjection.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryTextMatches.kt
    symbols: [QueryTextMatches]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ReacquiringQueryReferences.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/ReadAcquisitionAccounting.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/CurrentDeclarationReacquisition.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactRevalidation.kt
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedExactRevalidationStore.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactRevalidationCapture.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/AdmittedReadRejections.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/ReadRecoveryAction.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceQualifiedProgressDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceReadOutcomeDocuments.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceProgressProjection.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedSourcePreparedCoverage.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryRunQualification.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryQualifiedProgressDocument.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryProgressProjection.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPreparedCoverage.kt
  - path: query/protocol/build.gradle.kts
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
    symbols: [CanonicalQueryProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
    symbols: [QueryOutcomeProjection]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryProjectionRows.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryProjectedEvidence.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryResultPresentation.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryPresentationWindowMapping.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryResultEnvelope.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryProjection.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryRetainedPresentationWindow.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QuerySyntaxAdmission.kt
    symbols: [evidenceBasis]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateRecords.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryPublicationTransition.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateRetention.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateContracts.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryExecutionPublication.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryDiscoveryObservation.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationReferenceOccurrence.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedEvidence.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryItemProjector.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryBindingDocuments.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultDocuments.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryWalkProjection.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSourceReadProtocol.kt
    symbols: [CanonicalSourceReadProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalDiagnosticCheckProtocol.kt
    symbols: [CanonicalDiagnosticCheckProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt
    symbols: [QueryReferenceAuthority]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSelectorDocumentAdmission.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryReferences.kt
    symbols: [CanonicalQueryReferences]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelectorToken.kt
    symbols: [SourceSelectorTokenCodec]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt
    symbols: [CandidateSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolSearchScope.kt
    symbols: [SymbolSearchScope]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceSearchScopeModel.kt
    symbols: [WorkspaceSearchScopeModel]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/LiveReadOutputSchemaTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceTransport.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
sources:
  - id: openwiki-source-843b2f72b64738a9d114aff8
    resource: repo://protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryRetainedPresentationWindow.kt
  - id: openwiki-source-ceaa6e4a1cc8af84eede173b
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactRetainedGraph.kt
  - id: openwiki-source-47b84d48b89b57b3b1609484
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPresentationExecution.kt
  - id: openwiki-source-df5814ea9050f768658bd6bc
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResultCapture.kt
  - id: openwiki-source-dfd865ab52ce8eea1b519c4e
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  - id: openwiki-source-da029c0f3804840096380efa
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryImpactSourceAdmission.kt
  - id: openwiki-source-b51014e0385264d1b67f03cd
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
  - id: openwiki-source-bff1faad340ec1120efffe0b
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryPresentedResultIssuance.kt
  - id: openwiki-source-96ef904abd7028335557559c
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryPresentedWindowSelection.kt
  - id: openwiki-source-2ccdc01e43e3898d9dd2e63f
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryResultPresentation.kt
  - id: openwiki-source-4bf64022307aff9f9f531f9b
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt
  - id: openwiki-source-11b3793b16152a8db22d714e
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/OriginalImpactRetentionTest.kt
  - id: openwiki-source-8bba742d3ecbc32c906815e6
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
  - id: openwiki-source-711e20b0c3995766bf099120
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryValuePathOutputAdmission.kt
  - id: openwiki-source-680008eb9e24b45cf91f6d9d
    resource: repo://runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
generated: { by: "codex", at: "2026-10-03T08:10:55.203Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-03T08:10:55.203Z
---

# Query protocol

Scoped `SEARCH_TEXT` lowers to the canonical `TEXT_WORD` source. Exact symbol
rows carry bounded `INDEXED_WORD` match context beside their reusable refs.
Projection retains the canonical file identity and UTF-16 occurrence range;
match evidence does not admit `OCCURRENCES` output. Retained-result presentation
preserves these matches without invoking semantic providers. Identity-based
distinct and union keep the first row's relation arrival and merge its lexical
exemplars from later rows with the same exact identity.

`query:protocol` owns reusable admission and projection for query, symbol, source,
and diagnostic reads. Its dependencies are domain contracts
and canonical protocol contracts. It has no IntelliJ project, workspace opener,
native publication authority, or worker capability. Its detached state store does not grant semantic authority.

The owning host supplies the current `SemanticReadAuthority`, operation ports,
and budgets. `CanonicalQueryProtocol` restores input references, admits the typed
query plan, and executes the supplied `QueryOperations`. `QueryOutcomeProjection`
projects complete, qualified, or rejected results and retained presentations.
The other read protocols use the same reference
and evidence vocabulary around their domain operations. The existing-IDE host uses this boundary; historical published-evidence tests exercise the same contracts without granting a production publication owner.

Diagnostic progress retains the requested path alongside file inventory and analyzed files. The CLI derives discovered, analyzed, skipped, and exhaustive coverage from that progress, and labels the result as IDE file diagnostics.

`QueryReferenceAuthority` separates reference issuance and restoration from
execution authority. Published references retain their generation. Live
references require a freshly admitted authority from the original host; decoded
root, host, epoch, version, and content view must match it. Restoration does not
open an IDE or prove a declaration is current. Native read adapters must still
revalidate scope, location, compiler evidence, and content.

Run admission reacquires exact references from the source and `concat` steps through one bounded request capability. An invalid composed token reports its step and position. Retained symbol and binding result sources restore immutable, basis-bound rows with their original qualification, including an empty result, without rediscovery. Issued row IDs can select a subset of one owning result; unknown or foreign row IDs reject, and a proper subset records incomplete selection. `intersect` and `difference` accept retained symbol right operands; plan admission rejects `difference` when the right input lacks complete coverage, has failures, or retains producer progress. Canonical admission refines bounded binding names and restores each join right input from a retained result. Invalid row-kind transitions, unknown projected bindings, and incompatible output modes reject before domain effects. `concat` appends inputs; a following `distinct_symbols` retains the first row for each canonical identity. An inner join projects a typed pair of named symbol or proven occurrence cells. `project_binding` selects one named cell before symbol stages continue, including when the input is a selected retained binding result. The binding-row document checks equal canonical symbol IDs and verifies each occurrence fact against its cell symbol connections; wire decoding rejects forged pairs. Retained results distinguish symbol, occurrence, traversal, and binding rows and preserve column names even when no rows match. Reference occurrences retain compiler-confirmed targets separately from declaration ownership. Declaration-only composition requires a proven declaration cell; file-scoped imports cannot become declaration or call graph endpoints. Binding rows cannot serve as symbol join right inputs; read-result requires the matching output kind while preserving row IDs and evidence. A resume action supplies only the issued continuation and optional new grant; `QueryStateStore` restores the admitted plan, authority and pending work. Old tokens and completed stages are not reacquired on each page. A read-result action uses a distinct result reference and optional presentation cursor to page retained rows without invoking semantic providers.

Selector documents retain directory, package, declaration-kind, and exact Gradle
source-set constraints for source-owned declaration candidates and exact symbols.
Relation occurrences and diagnostic locations issue range candidates consumed by
internal source extraction; these do not grant exact symbol authority. No public file candidate
or discovery-batch token issuer remains. Published selectors use version 2;
live or scoped selectors use version 3. A version-2 decoder rejects payloads
requiring newer authority or scope evidence. Modeled source-set selection uses
exact named constraints on a workspace scope.

Successful projection chooses `EvidenceBasis.Published` or `EvidenceBasis.Live`.
The live variant carries detached provenance and cannot enter a published write
or topology admission. See [source identity](../contracts/source-identity.md),
[semantic query](../flows/semantic-query.md), and
[operation outcomes](../contracts/operation-outcomes.md).

An exact query's optional `SOURCE` field carries bounded normalized text and an inclusive one-based line range. Its projection admits the existing source-text and line-range types before wire encoding. Malformed source text or line coordinates reject at wire decoding; a missing requested window is reported through the finite source item failure and `SOURCE_INCOMPLETE` query qualification.

Query walk retains the domain traversal continuation inside the bounded query
execution checkpoint while an expansion is unfinished. The query continuation
restores the original plan, authority, and remaining work; no separate public
traversal or relation token is issued. Query relation expansion returns
individual occurrence items and structured omissions through `query.run`.

`QueryReferenceTransport` separates detached token representation from canonical decoding. Hosted exact and candidate references normally use version-5 handles (31 and 35 characters); lookup restores the full version-2/3 token before the existing authority, scope and evidence checks. Short-digest collisions return the inline selector and retain the prior handle. Canonical query documents retain `symbol_id` internally for snapshot-local declaration equality across admitted scopes. The hosted model projection omits it; `distinct_symbols` and set stages use the canonical equality owner without exposing an equality key. Published test composition retains inline transport by default. Source declaration identities and candidate targets use the same host issuer and preserve its returned candidate token unchanged; source-read success does not upgrade candidates to exact references. Source snapshot tokens retain their codec. `QueryStateStore` shares one bounded, expiring quota among typed execution checkpoints, immutable retained results, detached output suffixes, and request claims; their tokens and restoration outcomes remain separate. Each producer token admits one active claim. Concurrent use yields `CONTINUATION_IN_USE`; a published page replays its immutable facts and successor without semantic work. Hosted execution stages allocations under the claim, then commits the fitted page and its successor after freshness, deadline, and cancellation drainage. Discard releases only that attempt's allocations. Committing a producer replaces its consumed execution or output payload with the immutable published page and required dependency identities; the consumed payload no longer counts as retained work. Live pages retain their dependency closure under the same quota. Each token keeps its original age; a younger page remains usable only while all advertised dependencies are valid. Active claims pin physical storage through drainage without admitting expired inputs to a new request. Initial composition pins its exact inputs and rechecks their original age at publication, including discarded output. Replay never renews token age. Capacity and unavailable state produce finite rejection rather than a native restart. An unfinished query walk keeps its domain continuation with strategy, maximum depth, cumulative progress, and earlier provider limitations inside that execution checkpoint.

Query relation omission projection retains the exact subject and relation,
provider/version, page-local observed or unmeasured omissions, bounded source
samples and a closed remediation. Budget stops qualify that page's unmeasured
remainder; they do not manufacture observed missing facts. Known occurrence
facts remain separate from incomplete enumeration. Query walk
projection retains cumulative progress, independent reference occurrences, historical omissions, and page-local partial node expansions;
a bounded-fan-out remainder is explicitly unexamined rather than silently absent. Traversal checkpoints carry complete omission objects, including provider, measured or unmeasured meaning, bounded sample, and remediation, through later pages. Query filters, retained inputs, set stages, and joins preserve producer observation provenance independently of emitted rows.

Query qualification owns mandatory closed execution progress: resumable with an upstream checkpoint or retained-output checkpoint, or terminal-incomplete with a finite reason. A retained-output checkpoint reports the original upstream coverage, preserving terminal reasons without asserting that an interrupted scan can resume. An admitted upstream checkpoint permits resumption even when the page emits no rows; retained-output fitting can require an increased allowance. `QueryRunResult` separately reports retention outcome and an optional result presentation cursor. This cursor cannot resume execution; CLI compatibility fields for execution progress are derived from qualification. Wire decoding rejects missing progress and noncanonical checkpoint families.

Source qualifications own closed resumable or terminal-incomplete progress. Native
source checkpoints and hosted retained-output checkpoints are separate variants;
retained output preserves original complete, resumable, or terminal coverage.
Legacy cursor availability is derived from that authority. An admitted native
cursor permits resumption even when the page emits no entities; retained-output
fitting can require an increased allowance. A terminal text-withheld explanation
requires a matching text-byte limitation; other upstream gaps remain finite
terminal evidence. Source wire admission rejects missing progress, unsupported
variants and mismatched checkpoint families.

An admitted rejection in query or source carries its existing
finite reason plus the required execution-budget report. Missing metadata retains
the unadmitted failure variant; null or malformed reports reject at the boundary.
The [outcome contract](../contracts/operation-outcomes.md) separates this admission
evidence from successful semantic results.
Canonical read failures derive a closed recovery direction from their reason.
Canonical rejected CLI/tool documents require `next_action` both before and after
budget admission; an admitted budget report does not change that action. This
projection does not restore references, consume continuations, or convert a
rejection into progress.
See [operation outcomes](../contracts/operation-outcomes.md) for the action contract.

Source continuation admission preserves finite causes before invoking the provider:
missing, expired, evicted, or retired tokens yield `CONTINUATION_UNAVAILABLE`;
a changed context yields `SOURCE_SNAPSHOT_MISMATCH`; a changed request yields
`CONTINUATION_REQUEST_MISMATCH`. Provider contract failures remain distinct.

Source output can explicitly select compact presentation while expanded remains
the compatibility default. The protocol maps that choice exhaustively into a
native continuation compatibility witness; it does not change source semantics.
See [source identity](../contracts/source-identity.md#source-output-format).

## Explicit exact reacquisition

Fresh first-page query and source requests may reacquire an exact reference after
strict restoration reports a stale handle. The previous exact token is only a key into the running owner's
separate detached locator store. Current admission, the same host/root and model
source owner, unchanged saved committed owning-file bytes, and one fresh exact K2
match are required. The service checks freshness again before new issuance.
No prior source snapshot, relation, continuation or mutation approval becomes
current through reacquisition; those paths retain strict restoration.

Capture is optional for ordinary issuance: it runs inside exact compiler lookup,
deduplicates at most 64 files per request, and retains no source payload or platform
object. Each file is limited to 1 MiB and charged one work unit plus one per 4 KiB
of bytes actually read against the admitted capture allowance. Admission reserves
room for a single EOF probe byte; length movement rejects with finite data.
IntelliJ charset/BOM/newline decoding must match both compiler PSI text and any
cached committed document text before capture can be retained. Saved/committed
flags alone do not prove this equality. Missing captures remain unavailable.

The hosted store retains at most 256 tokens with a conservative 4 MiB charge.
Records remain usable for five minutes; replay does not renew retained entries.
When a new epoch needs capacity, older-epoch records are evicted before current
records. Expired retained tokens and evicted or unknown tokens have distinct
failures. This store does not search moved files, recover candidates, persist
across owners, or restore tokens issued before capture was installed.

## Automatic acquisition for fresh reads

Fresh query-reference and source-symbol reads use
`ReacquiringQueryReferences`. A missing or stale exact handle can trigger one
bounded lookup per handle in the same invocation. The original owner, file,
scope, kind, name and constraints remain fixed. Exact-name native indexes are
restricted to the owning file before candidate collection. K2 must find one
matching declaration identity and signature; changed offsets or body contents
are allowed. Missing, ambiguous, unsupported and changed compiler identities
remain finite rejections. Compiler refinement runs outside native index callbacks.

Capture, candidate work and elapsed recovery time consume the same request grant
as the semantic operation. Exhaustion remains a distinct work- or time-limit
failure. Successful results optionally carry `reference_acquisitions`, including
qualified partial results, so the caller can retain the fresh handle.

Continuation restoration, explicit original-document inspection, source snapshots
and change planning retain strict authority. A refreshed read handle cannot
refresh a previous page or authorize a write against changed document content.

Source reads retain [precise failure origin](../contracts/source-failures.md) through admission and serialization. Their admitted rejection wrapper retains the complete cause, including internal obligations and finite reference lookup evidence.

Impact source admission parses the expansion domain and closed model syntax before native acquisition. One aggregate grant owns seed, declaration and boundary-site revalidation. The admitted plan retains those current proofs, so resume restores them rather than reacquiring seeds or bindings. The existing query-state result retains the investigation ledger, value-path row kind and original closure; witness sections are presentations of that ledger.

The current public source-admission boundary accepts one supplied semantic authority. Foreign repository bases fail closed before modeled connection; the domain boundary contract can retain separate source and target bases, but this host path does not establish a second repository's current proof. Model syntax, compiler binding, reviewed representation meaning and retained presentation remain distinct evidence levels.

## Original impact result retention

An original `IMPACT` producer request with retention enabled captures every path
from its admitted immutable investigation ledger, independently of the initial
output selection. An empty initial page caused by output capacity does not turn
the retained result into an empty path set. Capture preserves the original
question, semantic basis, coverage, producer progress and ledger accounting.
Ordinary queries over explicitly selected retained rows keep their selected
membership; they do not expand back to every path in the original investigation.

The existing `QueryStateStore` issues row identities for the full original path
set. Initial presentation selects those identities by exact ordered path
evidence and original ordinal, so a reordered selection such as `[2, 0]` retains
the identities of original rows 2 and 0. A contiguous initial prefix carries the
original retained result end: zero emitted rows may expose presentation cursor
0, and a one-row prefix may expose cursor 1. Neither cursor permits semantic
execution to resume. A noncontiguous selection carries a finite cursor
qualification through prefix and suffix fitting and cannot imply a contiguous
next cursor. `READ_RESULT` from cursor 0 can still inspect the full original
retained result.

Retained value-path and witness pages use the existing result reference, store
and presentation cursor. They project detached ledger evidence without
reacquiring producers, revalidating models or executing value-flow reads.
Draining those pages preserves unresolved original obligations and terminal
execution reasons, including `OUTPUT_ITEM_TOO_LARGE`; presentation does not
manufacture complete investigation coverage. The production projection, store
and read-result regressions cover empty initial output, contiguous prefixes,
reordered selections and ordinary selected-result capture with a semantic
executor that must remain unused. Those tests establish the retained contract,
not installed IDE qualification.

Impact retained-storage charge uses a request-local visitor over actual shared
immutable objects, separate from row identity and compiler identity. References
and list cells remain charged; equal copies remain separate allocations. The
visitor changes neither the query-state owner nor its quotas and safety factors.
It is conservative admission arithmetic rather than a measurement of heap use.

Hosted value-path output uses a scoped evaluation capability paired with the
existing encoded-envelope fitter. The capability is retired before fitting and
cannot be carried into checkpoints. Standalone service execution keeps its
conservative byte guard; storage limits remain independent of output bytes.
