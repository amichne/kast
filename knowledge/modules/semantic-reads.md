---
type: Kotlin Module Group
title: Semantic read domains
description: Domain contracts refine discovery into exact compiler identity and compose source, relation, traversal, diagnostics, and queries without erasing evidence.
resource: file://query
tags: [kotlin, semantic, query, compiler]
timestamp: 2026-09-10T00:00:00Z
code_sources:
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

Broad name discovery supplies a cached `IdFilter.getProjectIdFilter` before
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

The corrected scoped `ALL` path enumerates Kotlin declarations through the admitted file-type index without workspace name enumeration. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.
