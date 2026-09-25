---
type: Kotlin Module
title: Query protocol
description: Shared semantic-read admission and projection bind canonical requests and detached references to an authority supplied by the owning host.
resource: file://query/protocol
tags: [kotlin, protocol, query, authority]
timestamp: 2026-09-25T00:00:00Z
code_sources:
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ReacquiringQueryReferences.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/ReadAcquisitionAccounting.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/CurrentDeclarationReacquisition.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactRevalidation.kt
  - path: symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/ExactRevalidationService.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedExactRevalidationStore.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactRevalidationCapture.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/RelationCheckpointDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/TraversalCheckpointDocument.kt
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
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryItemProjector.kt
    symbols: [CanonicalQueryProtocol, evidenceBasis]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSymbolProtocols.kt
    symbols: [CanonicalSymbolDiscoverProtocol, CanonicalSymbolInspectProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSourceReadProtocol.kt
    symbols: [CanonicalSourceReadProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalTraversalRunProtocol.kt
    symbols: [CanonicalRelationReadProtocol, CanonicalTraversalRunProtocol]
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
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalRelationContinuationCodec.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
    symbols: [RelationContinuationDocument]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/InstalledServerProjectionDocuments.kt
  - path: cli/src/test/kotlin/io/github/amichne/kast/cli/LiveReadOutputSchemaTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalTraversalContinuationCodec.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/TraversalContinuationDocument.kt
    symbols: [TraversalContinuationDocument]
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/RelationContinuationCodecTest.kt
  - path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/RelationContinuationAuthorityTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceTransport.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
---

# Query protocol

`query:protocol` owns reusable admission and projection for query, symbol, source,
relation, traversal, and diagnostic reads. Its dependencies are domain contracts
and canonical protocol contracts. It has no IntelliJ project, workspace opener,
publication store, or worker capability.

The owning host supplies the current `SemanticReadAuthority`, operation ports,
and budgets. `CanonicalQueryProtocol` restores input references, admits the typed
query plan, executes the supplied `QueryOperations`, and projects complete,
qualified, or rejected results. The other read protocols use the same reference
and evidence vocabulary around their domain operations. The existing-IDE host uses this boundary; historical published-evidence tests exercise the same contracts without granting a production publication owner.

Diagnostic progress retains the requested path alongside file inventory and analyzed files. The CLI derives discovered, analyzed, skipped, and exhaustive coverage from that progress, and labels the result as IDE file diagnostics.

`QueryReferenceAuthority` separates reference issuance and restoration from
execution authority. Published references retain their generation. Live
references require a freshly admitted authority from the original host; decoded
root, host, epoch, version, and content view must match it. Restoration does not
open an IDE or prove a declaration is current. Native read adapters must still
revalidate scope, location, compiler evidence, and content.

Selector documents retain directory, package, declaration-kind, and exact Gradle
source-set constraints. Batch issuance preserves these facts for declaration,
file, and text candidates. Legacy unrestricted published selectors retain their
version-2 representation; live or scoped selectors use version 3. File/range
version-3 documents retain the original read scope and constraints; a version-2
document with those new fields rejects. Legacy decoding establishes the explicit
historical exact-file policy. These cases have separate codec regressions, so an
older representation cannot silently claim or discard a newer scoped history.

Default portable scope decoding admits workspace and exact-file scopes. For
source references, `CanonicalQueryReferences(model)` supplies a current
`WorkspaceSearchScopeModel` to source-token restoration, allowing modeled module,
project, and source-set scopes to recover only their matching strong owner
identities. Model-free restoration still rejects those scopes. Ordinary query
source-set selection is represented by exact named constraints on a workspace
scope.

Successful projection chooses `EvidenceBasis.Published` or `EvidenceBasis.Live`.
The live variant carries detached provenance and cannot enter a published write
or topology admission. See [source identity](../contracts/source-identity.md),
[semantic query](../flows/semantic-query.md), and
[operation outcomes](../contracts/operation-outcomes.md).

An exact query's optional `SOURCE` field carries bounded normalized text and an inclusive one-based line range. Its projection admits the existing source-text and line-range types before wire encoding. Malformed source text or line coordinates reject at wire decoding; a missing requested window is reported through the finite source item failure and `SOURCE_INCOMPLETE` query qualification.

Relation and traversal upstream continuations preserve their authority version:
published continuations use version 1 and live continuations use version 2. Each document
owns the accepted-version pattern shared by its input serializer and advertised
resumable output schema. Structural schema admission does not authenticate a
continuation. Decoding checks the authority-specific version and revision, while
owner admission retains subject, relation and scope checks. Terminal-incomplete upstream relation work has no upstream continuation; retained
output may still need draining while preserving that terminal coverage.
The public one-hop relation protocol retains the exact subject reference while
searching workspace destinations across files and packages. Its continuation
binds that expansion boundary, subject, and relation; a retained-subject cursor
cannot resume the expanded read.

Test-only fixtures in `workspace:contract` and `query:protocol` admit a fixed live
authority through its original owner and provide four ordered relation facts.
The CLI schema and query-protocol codec regressions share this fixture: a limit of three
produces an owner-issued continuation, and resume reaches the fourth fact under
the unchanged authority without consuming the prefix again. These tests start
no IntelliJ process and make no native provider-parity claim.

`QueryReferenceTransport` separates detached token representation from canonical decoding. Hosted exact and candidate references normally use version-5 handles (31 and 35 characters); lookup restores the full version-2/3 token before the existing authority, scope and evidence checks. Short-digest collisions return the inline selector and retain the prior handle. Canonical query documents retain `symbol_id` internally for snapshot-local declaration equality across admitted scopes. The hosted model projection omits it; explicit `distinct_symbols` uses the canonical equality owner without exposing an equality key. Published test composition retains inline transport by default. Source declaration identities and candidate targets use the same host issuer and preserve its returned candidate token unchanged; source-read success does not upgrade candidates to exact references. Source snapshot tokens retain their codec. Query continuations use a host-supplied bounded checkpoint store. Traversal tokens additionally bind strategy, maximum depth and cumulative progress; older tokens without these witnesses reject. Relation tokens retain earlier-page provider limitations even after the final page.

Relation projection retains provider/version, page-local observed or unmeasured
omissions, bounded source samples and a closed remediation. Budget stops qualify
that page's unmeasured remainder; they do not manufacture observed missing facts.
An eventual complete drain retains permanent provider omissions and its final
coverage independently of temporary page boundaries. `soundness` describes
the exact returned facts independently of incomplete enumeration. Traversal
projection retains cumulative progress and page-local partial node expansions;
a bounded-fan-out remainder is explicitly unexamined rather than silently absent.

Query qualification owns a mandatory closed progress state: resumable with an upstream checkpoint or retained-output checkpoint, or terminal-incomplete with a finite reason. A retained-output checkpoint reports the original upstream coverage, preserving terminal reasons without asserting that an interrupted scan can resume. Empty upstream pages explicitly require increased execution allowances. Query result payloads cannot carry independent cursor/terminal state; the CLI compatibility fields are derived from qualification. Wire decoding rejects missing progress and noncanonical checkpoint families.

Source qualifications own closed resumable or terminal-incomplete progress. Native
source checkpoints and hosted retained-output checkpoints are separate variants;
retained output preserves original complete, resumable, or terminal coverage.
Legacy cursor availability is derived from that authority. Empty native pages
require an increased execution allowance. A terminal text-withheld explanation
requires a matching text-byte limitation; other upstream gaps remain finite
terminal evidence. Source wire admission rejects missing progress, unsupported
variants and mismatched checkpoint families.

Relation and traversal checkpoints distinguish upstream work from hosted retained
output. The latter binds a detached suffix to the original complete, resumable,
or terminal-incomplete coverage. Its token family does not grant permission to
restart upstream work. The relation compatibility continuation is derived from
the checkpoint, and wire admission rejects conflicting tokens. Traversal token
admission verifies canonical payload encoding and digest before the owning
protocol checks authority, subject, strategy, scope and cumulative progress.

An admitted rejection in query, source, relation or traversal carries its existing
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

`symbol.inspect` accepts `revalidate_exact` alongside unchanged `candidate` and
`exact` targets. The previous exact token is only a key into the running owner's
separate detached locator store. Current admission, the same host/root and model
source owner, unchanged saved committed owning-file bytes, and one fresh exact K2
match are required. The service checks freshness again before new issuance.
Results encode `acquisition: strict` or `acquisition: reacquired` explicitly.
No prior source snapshot, relation, continuation or mutation approval becomes
current through this operation.

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

Fresh relation, traversal, source-symbol and query-reference reads use
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
