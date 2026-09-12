---
type: Kotlin Module
title: Query protocol
description: Shared semantic-read admission and projection bind canonical requests and detached references to an authority supplied by the owning host.
resource: file://query/protocol
tags: [kotlin, protocol, query, authority]
timestamp: 2026-09-12T00:00:00Z
code_sources:
  - path: query/protocol/build.gradle.kts
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
    symbols: [CanonicalQueryProtocol, evidenceBasis]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSymbolProtocols.kt
    symbols: [CanonicalSymbolDiscoverProtocol, CanonicalSymbolInspectProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalSourceReadProtocol.kt
    symbols: [CanonicalSourceReadProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalGraphProtocols.kt
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
  - path: runtime/composition/src/test/kotlin/io/github/amichne/kast/runtime/composition/protocol/graph/RelationContinuationCodecTest.kt
  - path: runtime/composition/src/test/kotlin/io/github/amichne/kast/runtime/composition/protocol/graph/RelationContinuationAuthorityTest.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalTraversalContinuationCodec.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/TraversalContinuationDocument.kt
    symbols: [TraversalContinuationDocument]
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
and evidence vocabulary around their domain operations. Runtime composition and
the existing-IDE host can reuse this boundary without sharing their admission
effects.

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

Relation and traversal continuations preserve their authority version: published
continuations use version 1 and live continuations use version 2. Each document
owns the accepted-version pattern shared by its input serializer and advertised
resumable output schema. Structural schema admission does not authenticate a
continuation. Decoding checks the authority-specific version and revision, while
owner admission retains subject, relation and scope checks. Terminal-incomplete
relation output carries no continuation.

Test-only fixtures in `workspace:contract` and `query:protocol` admit a fixed live
authority through its original owner and provide four ordered relation facts.
The CLI schema and runtime codec regressions share this fixture: a limit of three
produces an owner-issued continuation, and resume reaches the fourth fact under
the unchanged authority without consuming the prefix again. These tests start
no IntelliJ process and make no native provider-parity claim.
