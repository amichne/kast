---
type: Runtime Flow
title: Semantic query
description: Query syntax and restored references are admitted into compatible stages and evaluated under one published or live authority with bounded resource accounting.
resource: file://query/service
tags: [query, symbol, source, relation]
timestamp: 2026-09-25T00:00:00Z
code_sources:
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QuerySyntaxAdmission.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryWalkEvidence.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryStepModels.kt
  - path: app-server/src/main/resources/io/github/amichne/kast/appserver/query/tools.schema.json
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryRetainedCompositionTest.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryContinuations.kt
  - path: app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolContract.kt
  - path: protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/CanonicalAgentToolDefinitions.kt
  - path: docs/reviews/live-semantic-read-acceptance.md
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/ide/ExistingIdeCli.kt
    symbols: [selectCliRuntimePath]
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
    symbols: [QueryPlanCompiler, AdmittedQueryPlan]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QuerySteps.kt
    symbols: [QueryStepSyntax, QueryPredicate]
  - path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt
    symbols: [QueryExecutionState]
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
    symbols: [QueryService]
  - path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryWalkProjection.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryIdentityRows.kt
  - path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryServiceSupport.kt
    symbols: [visibilityRequest]
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryServiceTest.kt
  - path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryServiceSourceTest.kt
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
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
  - path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
---

# Semantic query

```text
host admission -> current authority + request budget
              -> closed run / resume / read-result action
run           -> discovery, exact references, or retained semantic rows
              -> exact stages -> bounded projection -> optional retention
resume        -> saved execution checkpoint -> remaining exact stages
read-result   -> immutable retained rows -> presentation page
```

The pure plan compiler admits only exact-symbol stages and selects discovery, exact references, or a retained symbol result as its source. Discovery still refines internal candidates before a row enters the exact pipeline. Execution tracks `SemanticReadAuthority`, time, work units, encoded bytes, result capacity, limitations, and item failures. A live authority is supplied by its admitted host; decoding a reference never creates one. Query output selects exact symbols with requested fields, one row per relation occurrence, depth-bearing traversal records, or typed binding rows from a terminal inner join. Query candidate output and its inspect stage have been removed; separate symbol lookup and inspection still own those capabilities.

The `where` stage admits a closed visibility or primitive predicate. Primitive predicates compare compiler-grounded name, kind, or file text without a source read. `concat` admits exact references or retained rows at any stage and schedules them after the upstream stream, preserving order and multiplicity. Later predicates and relation hops see both inputs. `intersect`, `union`, and `difference` use canonical semantic identity; matching rows merge evidence, while `difference` requires complete right coverage without failures or producer progress to establish absence. `distinct_symbols` removes repeated identities only where requested. Composed inputs share the parent work and checkpoint budget. A resume action supplies only an issued execution continuation and optional new grant. `QueryStateStore` restores the admitted plan and pending work, so completed prefix stages and old tokens are not reacquired.

`bind` seals an ordered symbol snapshot under an earlier-only query-local name. `join` reads that binding or a retained symbol result on the right using canonical symbol identity. The join index stores row positions, so inner join emits every matching pair with its two named symbol or proven occurrence cells; it never collapses repeated rows. Terminal inner join requires binding-row output. Semi join merges matching evidence into the surviving left symbol, while anti join emits a left symbol only after complete right membership proves no match. Incomplete retained right input rejects anti join; incomplete named input retains `JOIN_INPUT_INCOMPLETE` qualification and cannot establish absence. Query checkpoint state retains partially built binding and join indexes. `QueryRetainedResult` has separate symbol and binding-row variants, and read-result pages either type without rediscovering the prefix.

`walk` takes an admitted relation, maximum depth, and exploration strategy. The query service composes the existing traversal domain operation under its execution budget and host traversal ceiling. It retains reached rows with exact relation facts and depth; walk observations preserve strategy, expanded frontier, cumulative progress, partial expansions, and incomplete coverage. A domain traversal continuation stays inside the bounded query execution checkpoint while that stage is unfinished. The public query continuation resumes the pipeline; no standalone traversal operation or token is exposed.

`QueryRetainedResult` captures immutable symbol or binding rows, relation evidence and omissions, item failures, coverage, producer progress, and their semantic basis. `QueryStateStore` holds result references and execution checkpoints as distinct typed entries under one entry, byte, and lifetime bound. It issues row handles tied to each retained result, and the result projection returns those handles with retained rows. A run sourced from a retained symbol result seeds the service from proven rows, including an empty set, without rediscovery or re-description. Optional row IDs select original rows in order; a proper subset retains incomplete-selection qualification and cannot establish absence for excluded rows. Qualified positives remain usable with their original limitations and omissions. Restoration rejects unavailable, foreign-row, or stale-basis results. A requested retention can fail for capacity without erasing the current query output. The read-result action presents a bounded page from retained rows using a distinct result cursor; it does not invoke semantic providers.

An exact-symbol output may request `SOURCE`. At its emit stage, the service calls the existing source port with the same exact selector and read authority, a file region, no entity enumeration, and a fixed five-line window on each side. Returned text keeps its normalized committed-text proof and one-based line range. Source rejection or withheld text qualifies the query with a finite item cause; output and checkpoint bytes account for returned text. This adds one source read per emitted symbol and does not alter discovery refinement.

Discovery candidates are refined to exact symbols before query output. Query-local refinement preserves repeated rows in their source order; an explicit `distinct_symbols` stage groups equal canonical identities when requested. Failed refinements remain visible as limitations. Relation and traversal child budgets are derived from remaining parent capacity.

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

Native source declaration projection retains the requested kinds at the page owner.
Known excluded kinds skip visibility resolution and candidate construction, including
primary-constructor properties. Structural selectors, ranges, parents, and depths
remain outside that deferred projection so excluded containers can still contain
eligible descendants. Unavailable visibility for a selected declaration still
qualifies the read; visited PSI units still consume the native execution grant.

`CanonicalQueryProtocol` is shared by installed and existing-IDE composition.
It preserves per-item failures and qualifications and projects the matching
published or live evidence basis. `HostedCanonicalQuery` constructs the pure
evaluator with project-bound symbol, source, relation, and traversal ports inside an admitted
host read. `selectCliRuntimePath` chooses this existing-IDE path before installed
bootstrap for the five public semantic reads. The
[native acceptance review](../../docs/reviews/live-semantic-read-acceptance.md)
records the final CLI and production provider observations, including their
complete/qualified distinctions. Earlier class/supertype qualification remains
separate evidence.

See [query protocol](../modules/query-protocol.md), [semantic read domains](../modules/semantic-reads.md), and [compiler identity](../glossary/compiler-identity.md).

The [opt-in synthetic reproduction](../../docs/reviews/hosted-semantic-reproduction.md) independently verifies public identities, occurrences and coverage through both CLI and production provider. Qualified exact positives remain useful; zero items with relation incompleteness do not prove absence. The corrected expansion request preserves the selected subject and uses an explicit workspace search boundary for destinations; continuation fingerprints bind that boundary. Earlier exclusion receipts remain baseline evidence.

The corrected scoped `ALL` path enumerates Kotlin declarations through the admitted file-type index without workspace name enumeration. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Eager `query_symbols` owns declaration discovery and pipelines through `query.run`; `check_diagnostics` owns `diagnostic.check`. Hosted admission retains each tool's schema identity and typed syntax. The connected provider selects an enrolled workspace, then uses shared existing-IDE preparation and read dispatch. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.

Cheap scope and declaration-family constraints precede native collection. Mixed-family syntax issues one symbol discovery request with all requested kinds retained. Project-only fuzzy declarations use scoped Kotlin files, and exact searches select only requested short-name index families. Package PSI runs outside native index callbacks before candidate collection. Qualified partial results retain their limitations through exact refinement.

Hosted query projection issues compact exact-symbol handles before encoding.
The final byte guard accounts for actual serialized references and connections,
then retains any remaining transport output separately from query execution
checkpoints and requested immutable results. Resume restores the original plan
and semantic snapshot without retransmission. Expiry, eviction and mismatch
reject rather than restarting the query. The pure service retains an ordered
task stack, relation cursors, stage-local distinct and set identities, pending output
and finite upstream failures. Intermediate expansion does not consume final
projection byte capacity.

Distinct stages group rows by canonical declaration identity and merge their
relation connections before emitting one row per identity. A later duplicate
cannot mutate an already emitted page because the stage completes its group
before projection.
Incomplete upstream coverage remains qualified after the final buffered page.
An indivisible oversized output item, unavailable checkpoint capacity, or
unproven progress produces a finite terminal reason without a continuation.
