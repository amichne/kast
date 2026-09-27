---
type: API Contract
title: Source and workspace identity
description: Semantic reads preserve published or live authority, constrained selectors, coordinates, and source content views without converting IDE stamps into publication identity.
resource: file://source/contract
tags: [source, identity, workspace, symbol]
timestamp: 2026-09-16T00:00:00Z
code_sources:
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CompactSourceReadDocuments.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/SourceReadFormatProjection.kt
  - path: protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CompactSourceReadCliDocuments.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/provider/KastSourcePresentation.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CanonicalSymbolId.kt
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadLease.kt
    symbols: [CanonicalWorkspaceRoot, SemanticReadLease]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt
    symbols: [SemanticReadAuthority, SemanticReadIdentity]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/ExactDeclarationSelector.kt
    symbols: [ExactDeclarationSelector]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSnapshot.kt
    symbols: [SourceSnapshot]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadPort.kt
    symbols: [SourceReadContext, SourceReadContextPort]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadScope.kt
    symbols: [SourceReadScope]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
    symbols: [SourceDeclarationVisibility, SourceDeclarationVisibilityFailure]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadRequest.kt
    symbols: [Containment]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceSelectorToken.kt
    symbols: [SourceSelectorTokenCodec]
  - path: source/service/src/main/kotlin/io/github/amichne/kast/source/service/SourceReadService.kt
    symbols: [SourceReadService]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt
    symbols: [SymbolSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoverySourceSets.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolDiscoveryRequest.kt
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelector.kt
    symbols: [CandidateSelector]
  - path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/discovery/SymbolSearchScope.kt
    symbols: [SymbolSearchScope]
  - path: workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/WorkspaceSearchScopeModel.kt
    symbols: [WorkspaceSearchScopeModel, ModelOwnedSourceRoot]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryReferences.kt
    symbols: [CanonicalQueryReferences]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSelectorDocumentAdmission.kt
  - path: symbol/contract/src/test/kotlin/io/github/amichne/kast/symbol/contract/CandidateSelectorContractTest.kt
  - path: source/contract/src/test/kotlin/io/github/amichne/kast/source/contract/SourceSelectorTokenContractTest.kt
  - path: symbol/contract/src/test/kotlin/io/github/amichne/kast/symbol/contract/SymbolSelectorContractTest.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/Utf16Coordinate.kt
    symbols: [Utf16CodeUnitOffset, Utf16CodeUnitCount]
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/HostedSymbolHandle.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalSourceReadAnchorDocument.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SourceReadSimpleRequest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
---

# Source and workspace identity

A source read is not identified by path text alone. The contract combines:

- a physically canonical workspace root;
- published authority or original-owner live IDE authority;
- an exact compiler-grounded symbol or source selector;
- admitted UTF-16 coordinates and ranges; and
- a snapshot/content identity where source text matters.

`SourceReadContext.Published` retains a `SemanticReadLease` and the published
`WorkspaceStateIdentity`. `SourceReadContext.Live` retains the live authority and
its saved, PSI-committed content view. Both snapshots also retain the actual text
identity and UTF-16 length. Live context has no synthetic publication generation
or source-state identity. `SourceReadService` admits the context before the read
and revalidates it afterward before returning detached output.

Declaration selections and exact selectors retain their original scope,
directory and package restrictions, declaration kinds, and exact named Gradle
source sets. These fields participate in selector fingerprints. Their kind and
source-set collections are defensive immutable snapshots, so retained proof
cannot change after fingerprinting. Candidate-to-exact issuance checks that
compiler evidence satisfies the candidate's declaration-kind restriction;
relation-derived exact targets retain their separate issuance path.

Source-owned declaration candidates preserve discovery scope and constraints.
Relation and diagnostic locations issue range candidates with explicit exact-file
scope. Neither candidate family grants compiler identity. `source_read` refines
these locations through its current authority and committed-document boundary.
The file-candidate family and batch candidate-token issuance have been removed.

`SourceReadScope.Constrained` carries every candidate or exact selector's scope
and restrictions into the source snapshot. Source selectors bind this scope in
their fingerprints and reject revalidation against a changed scope. The
`ExactFile` variant remains available for legacy stored source snapshots.

Legacy published exact-file source tokens retain version 1. Live or constrained
source tokens use version 2. Live restoration requires the current authority
from the original owner and compares root, host, epoch, version, and content
view. A valid token still requires native scope and content revalidation before
reading. Default portable scope restoration supports workspace and exact-file
scopes. The model-aware decode overloads also restore module, Gradle-project,
and source-set scopes through `SymbolSearchScope.restore(model, snapshot)`.
That transition uses only strong owner identities present in the supplied
`WorkspaceSearchScopeModel`. Absent owner evidence, a different owner, or a
foreign model root rejects; the model is not reconstructed from token strings.

Visibility predicates use `SourceDeclarationVisibility` to prove the selected
declaration's own visibility in a complete exact source result. Its internal
`Containment.SELF` request requires a symbol anchor and its declaration region;
the source service rejects a complete result without that matching self evidence.
The proof binds the snapshot, range and semantic candidate to the exact selector.
Direct-child source containment keeps its existing meaning.

See [query protocol](../modules/query-protocol.md), [compiler identity](../glossary/compiler-identity.md), and [workspace publication](../flows/workspace-publication.md).

Hosted version-5 symbol handles use bounded short lookup keys; version 4 remains
accepted. The project-owned table retains detached full tokens for the current
live read reference. Short-digest collisions retain the existing mapping and
return the new selector inline. Lookup must succeed before canonical restoration
validates the current authority. Unknown handles retain an unavailable cause in source reads; malformed
handles reject as malformed.

`CanonicalSymbolId` separately hashes the full compiler evidence and semantic
snapshot with SHA-256. It excludes discovery scope so equal declarations reached
through different admitted scopes share an internal canonical identity. Public query
results expose only `ref`; `symbol_id` has no public accessor. Exact selectors still bind
the original scope and constraints; the equality key cannot restore or broaden a
read capability. Native revalidation remains mandatory after lookup.

## Source output format

The canonical internal `SourceReadRequest.format` defaults to `expanded` for
existing anchor callers. The public hosted v4/v5 exact-symbol shortcut uses an admitted
`ExactSymbolSelector` and defaults to `compact`, declaration region, complete
text, no entities, and the first page. Its typed request normalizes to the same
canonical source read; a malformed or wrong-family selector fails admission.
Selecting `compact` changes
representation without changing source enumeration or reference authority.
The hosted public source intent accepts an unchanged exact `anchor.symbolRef`,
declaration region, text mode and entity mode, then lowers to the canonical
request with compact format. Entity-free intent has no entity limit field.
For an exact declaration reference, the public `declaration` region selects
that reference's anchor. Internal source wire encoding still includes the
default entity limit, text byte limit, and first page for installed host
compatibility; these fields are not required in the public request.
Compact responses return each distinct source selector once in a response-local
selection table. Integer IDs join selections, parents, callees, and local targets
to that table; they are not valid follow-up selectors. Pass the table entry's
`selector` unchanged to another source read. Candidate target tokens remain
host-issued candidates, independently of table IDs.

Every encoded page carries its own table, snapshot, selected region, ordered
entities, and text state. Wire decoding expands the table back into the same
canonical source result and rejects invalid or noncanonical table references.
Both native and retained-output continuations bind the chosen format. Request a
new first page when changing formats.

Compact CLI output contains ordered `source` then `structure` content sections.
The production provider presents returned source bytes before the structured
result; expanded output retains its existing shape. Hosted fitting measures the
chosen wire envelope, including table and continuation metadata. If indivisible
returned text prevents a page from fitting, a qualified `withheld` text state
retains the selected region and reports the text-byte limitation explicitly.

The production provider receives the process envelope containing the source document.
Compact presentation reads that admitted inner payload, emits unchanged returned
source first, and retains the complete original envelope as the final content item.
The isolated native read harness checks this actual content ordering separately
from schema validation and reports bounded content byte counts without source text.

## Source failure origin

Source failures preserve their origin through canonical outcomes, admitted budget
reports, wire serialization, CLI documents, and provider input rejection. See
[source failures](source-failures.md) for the closed request, reference, and
internal-obligation contract. Unknown handles do not prove expiry or foreign
ownership.

Fresh symbol-anchored source reads can reacquire a retained exact symbol in the
same invocation and return `reference_acquisitions`. This is a new read of the
current declaration, not restoration of the prior source snapshot. Source-anchor
and continuation reads keep their original snapshot checks. See
[automatic acquisition](../modules/query-protocol.md#automatic-acquisition-for-fresh-reads).
