---
type: API Contract
title: Source and workspace identity
description: Semantic reads preserve published or live authority, constrained selectors, coordinates, and source content views without converting IDE stamps into publication identity.
resource: file://source/contract
tags: [source, identity, workspace, symbol]
timestamp: 2026-09-10T00:00:00Z
code_sources:
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
    symbols: [Utf16Coordinate]
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

File and text candidates also retain scope and constraints. Their batch-backed
factories select a candidate by a checked ordinal and preserve the batch's read
restrictions. Historical raw factories retain an explicit exact-file scope with
production-and-test, generated-source inclusion, and no discovery constraints;
they do not claim an unavailable discovery history.

File/range selector version 3 retains the scope and constraints in its read-scope
document. A version-2 document cannot carry those new fields; omitting them retains
only the explicit historical exact-file semantics. Batch issuance and restoration
therefore preserve the same proof rather than silently widening a source read.

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
