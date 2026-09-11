---
type: Runtime Flow
title: Semantic query
description: Query syntax and restored references are admitted into compatible stages and evaluated under one published or live authority with bounded resource accounting.
resource: file://query/service
tags: [query, symbol, source, relation]
timestamp: 2026-09-11T00:00:00Z
code_sources:
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
    symbols: [QueryPlanCompiler, AdmittedQueryPlan]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt
    symbols: [QueryExecutionState]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
    symbols: [QueryService]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryServiceSupport.kt
    symbols: [visibilityRequest]
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryServiceTest.kt
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
    symbols: [SourceDeclarationVisibility, SourceDeclarationVisibilityFailure]
  - path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadRequest.kt
    symbols: [Containment]
  - path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/LiveIntellijSourceRead.kt
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijExactNameIndexes.kt
    symbols: [discoverNative]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryQuery.kt
    symbols: [IntellijNativeDiscoveryQuery]
  - path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryAdapter.kt
    symbols: [isAdmittedContributorName]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
    symbols: [CanonicalQueryProtocol]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt
  - path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
---

# Semantic query

```text
host admission -> current authority + request budget
              -> query protocol/reference admission -> typed plan
              -> candidate discovery or restored references -> exact refinement
              -> optional predicates/relations -> bounded result projection
```

The pure plan compiler prevents candidate-only and exact-symbol stages from being combined incorrectly. Execution uses a single request state to track `SemanticReadAuthority`, time, work units, encoded bytes, result capacity, limitations, and item failures. A live authority is supplied by its admitted host; decoding a reference never creates one.

Discovery may remain a candidate result. Exact-only operations force refinement, and failed refinements remain visible as limitations. Relation continuations and child budgets are derived from remaining parent capacity.

Broad native discovery now filters index names by the admitted scope's coarse
project-content or project-plus-library ID policy before the name cap, then
rechecks exact scope and constraints after collection. Its symbol provider set
includes type aliases alongside classes, functions, and properties. This changes
name admission and provider coverage without increasing query budgets or weakening
overflow qualification. The final packaged native rerun returned a qualified
minimum of 11 declarations for default/package `ALL`, including `helper` and the
`PublicChild` type alias. An exact property query completed although that property
was absent from `ALL`. This establishes partial native coverage, not exhaustive
enumeration or a general timing improvement.

Directory, package, declaration-kind, and named source-set restrictions are
retained through declaration, file, and text selection, exact fingerprints, and
source snapshots. Native adapters re-establish file membership against the current
model. Continuations retain the same authority identity, scope, and restrictions;
they cannot silently resume under a broader request.

A visibility predicate reads the selected declaration itself through internal
`Containment.SELF` with `VisibilitySelection.Any`. Native enumeration projects
that declaration once; it does not search its children for a matching visibility.
`SourceDeclarationVisibility` then requires one declaration with the matching
authority, file, scope, restrictions, exact range, kind, name, and candidate
location. A private parent cannot satisfy the predicate through a public child,
and a public leaf needs no children to satisfy it. Missing or mismatched evidence
becomes `PredicateUnproven` with `VISIBILITY_INCOMPLETE`. Public source grammar
continues to expose only direct-child and descendant containment.

`CanonicalQueryProtocol` is shared by installed and existing-IDE composition.
It preserves per-item failures and qualifications and projects the matching
published or live evidence basis. `HostedCanonicalQuery` constructs the pure
evaluator with project-bound symbol, source, and relation ports inside an admitted
host read. `selectCliRuntimePath` chooses this existing-IDE path before installed
bootstrap for the seven public semantic reads. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final CLI and production provider observations, including their
complete/qualified distinctions. Earlier class/supertype qualification remains
separate evidence.

See [query protocol](../modules/query-protocol.md), [semantic read domains](../modules/semantic-reads.md), and [compiler identity](../glossary/compiler-identity.md).

The [opt-in synthetic reproduction](../../docs/reviews/hosted-semantic-reproduction.md) independently verifies public identities, occurrences and coverage through both CLI and production provider. Qualified exact positives remain useful; zero items with relation incompleteness do not prove absence. The corrected expansion request preserves the selected subject and uses an explicit workspace search boundary for destinations; continuation fingerprints bind that boundary. Earlier exclusion receipts remain baseline evidence.

The corrected scoped `ALL` path enumerates Kotlin declarations through the admitted file-type index without workspace name enumeration. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Three ordinary searches and deferred `query_symbols` share `query.run`; `check_diagnostics` shares `diagnostic.check`. Private admission retains each tool's schema identity and typed syntax through its exact CLI binding. The `tool` command family uses the existing-IDE read path. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.
