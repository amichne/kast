---
type: Runtime Flow
title: Semantic query
description: Query syntax and restored references are admitted into compatible stages
  and evaluated under one published or live authority with bounded resource accounting.
resource: file://query/service
tags:
- query
- symbol
- source
- relation
code_sources:
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryTextMatches.kt
  symbols:
  - QueryTextMatches
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryReadStages.kt
  symbols:
  - QueryReadStages
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryTextDiscoveryStage.kt
  symbols:
  - QueryTextDiscoveryStage
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryTextMatchProjection.kt
  symbols:
  - protocolDocument
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoinStage.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRows.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijIncrementalDeclarationDiscovery.kt
- path: symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/SymbolDiscoveryProgress.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationProviderState.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijReferenceInventory.kt
- path: relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijRetainedRelationRead.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryDiscoveryObservation.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryPublicationSession.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryStateStore.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryOutcomeProjection.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QuerySyntaxAdmission.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedEvidence.kt
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
  symbols:
  - selectCliRuntimePath
- path: cli/src/main/kotlin/io/github/amichne/kast/cli/bootstrap/KastCliMain.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryPlan.kt
  symbols:
  - QueryPlanCompiler
  - AdmittedQueryPlan
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QuerySteps.kt
  symbols:
  - QueryStepSyntax
  - QueryPredicate
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt
  symbols:
  - QueryExecutionState
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryPageFacts.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryPageCompletion.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryDiscoveryTasks.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryService.kt
  symbols:
  - QueryService
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryWalkProjection.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryIdentityRows.kt
- path: query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryServiceSupport.kt
  symbols:
  - visibilityRequest
- path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryServiceTest.kt
- path: query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryServiceSourceTest.kt
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceDeclarationVisibility.kt
  symbols:
  - SourceDeclarationVisibility
  - SourceDeclarationVisibilityFailure
- path: source/contract/src/main/kotlin/io/github/amichne/kast/source/contract/SourceReadRequest.kt
  symbols:
  - Containment
- path: source/intellij/src/main/kotlin/io/github/amichne/kast/source/intellij/LiveIntellijSourceRead.kt
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijExactNameIndexes.kt
  symbols:
  - discoverNative
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryQuery.kt
  symbols:
  - IntellijNativeDiscoveryQuery
- path: symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/discovery/IntellijNativeDiscoveryAdapter.kt
  symbols:
  - isAdmittedContributorName
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt
  symbols:
  - CanonicalQueryProtocol
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryReferenceAuthority.kt
- path: relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedCanonicalQuery.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt
- path: runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedReferenceStore.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactFinding.kt
- path: query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactWitness.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingProjection.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingTerminalProjection.kt
- path: query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingQualificationTest.kt
- path: query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryResultRetentionSource.kt
sources:
  - id: openwiki-source-25796dce45aaa5a543a07570
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryCompleteMembership.kt
  - id: openwiki-source-927d5002042f13cac9db37f1
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactFinding.kt
  - id: openwiki-source-9fcd90db4b894268639790f3
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactLedger.kt
  - id: openwiki-source-ceaa6e4a1cc8af84eede173b
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactRetainedGraph.kt
  - id: openwiki-source-8abcd99add0585ea126a3883
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactSiteAccounting.kt
  - id: openwiki-source-f0cad133a15126760b814619
    resource: repo://query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryImpactWitness.kt
  - id: openwiki-source-922a5bf331e56677e884867b
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingProjection.kt
  - id: openwiki-source-37ab3b1971d180b6eee551f9
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingTerminalProjection.kt
  - id: openwiki-source-d531f9a1f24035c5d61d45d5
    resource: repo://query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryResultRetentionSource.kt
  - id: openwiki-source-89a23fbeb799d9605ab4e1dc
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/ImpactFindingQualificationTest.kt
  - id: openwiki-source-ff3a32a34def3fbe81d63b1c
    resource: repo://query/protocol/src/test/kotlin/io/github/amichne/kast/query/protocol/PendingImpactReadResultTest.kt
  - id: openwiki-source-8bba742d3ecbc32c906815e6
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/PipelineCheckpoint.kt
  - id: openwiki-source-a0bad9b6f38ff3fd5d037242
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactCapacity.kt
  - id: openwiki-source-61fafb2f3dea54cb612abc88
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactObservedArrivals.kt
  - id: openwiki-source-02892939c89f7aefe94224ab
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactSnapshot.kt
  - id: openwiki-source-2055be6c2f31ab5f9c9cda31
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryImpactTasks.kt
  - id: openwiki-source-22c063698f200893b9da9c83
    resource: repo://query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryJoins.kt
generated: { by: "codex", at: "2026-10-03T14:16:26.318Z" }
verified:
  - by: openwiki/0.6.1
    at: 2026-10-03T14:16:26.318Z
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

The pure plan compiler admits symbol, occurrence, traversal, and binding-row stages and selects declaration discovery, containing named declaration at file offset, exact references, or a retained symbol or binding result as its source. Discovery refines internal candidates before a row enters the exact pipeline. Execution tracks `SemanticReadAuthority`, time, work units, encoded bytes, result capacity, limitations, and item failures. A live authority is supplied by its admitted host; decoding a reference never creates one. Query output selects exact symbols with requested fields, one row per relation occurrence, depth-bearing traversal records, or typed binding rows.

At public admission, every tagged source, scope, action, output, predicate, and strategy variant has a required `type` with a `CAPS_CASE` value. Singleton tags declare matching schema defaults in the full MCP projection. Typed facade DTOs lower these values to canonical query documents before plan compilation; canonical wire spellings remain internal.

The `where` stage admits a closed visibility or primitive predicate. Primitive predicates compare compiler-grounded name, kind, or file text without a source read. `concat` admits exact references or retained rows at any stage and schedules them after the upstream stream, preserving order and multiplicity. Later predicates and relation hops see both inputs. `intersect`, `union`, and `difference` use canonical semantic identity; intersection combines bilateral evidence, while `difference` requires complete right coverage without failures or producer progress to establish absence. `union` lowers to concat followed by distinct. `distinct_symbols` keeps the first row and its evidence for each canonical identity, including across resume. Composed inputs share the parent work and checkpoint budget. A resume action supplies only an issued execution continuation and optional new grant. `QueryStateStore` restores the admitted plan and pending work, so completed prefix stages and old tokens are not reacquired.

`join` reads a retained symbol result on the right using canonical symbol identity. The join index stores row positions, so inner join emits every matching pair with its two named symbol or proven occurrence cells; it never collapses repeated rows. `project_binding` selects one named cell before symbol-only stages continue. Semi join merges matching evidence into the surviving left symbol, while anti join emits a left symbol only after complete right membership proves no match. Incomplete retained right input rejects anti join during admission. Query checkpoint state retains partially built join indexes. `QueryRetainedResult` has separate symbol, occurrence, traversal, and binding-row variants, and read-result pages those types without rediscovering the prefix.

`walk` takes an admitted relation, maximum depth, and exploration strategy. The query service composes the existing traversal domain operation under its execution budget and host traversal ceiling. It retains reached rows with exact relation facts and depth; walk observations preserve strategy, expanded frontier, cumulative progress, detailed partial expansions and historical omissions, independent non-expandable reference occurrences, and incomplete coverage. Independent proof payloads become bounded evidence units before output accounting; repeated progress fields are shared witnesses, and consumers must not add them together. This lets occurrence rows and their traversal provenance drain under small byte grants without repeating native expansion. A domain traversal continuation stays inside the bounded query execution checkpoint while that stage is unfinished. The public query continuation resumes the pipeline; no standalone traversal operation or token is exposed.

`QueryRetainedResult` captures immutable rows, discovery and reference observations, relation evidence and omissions, item failures, coverage, producer progress, and their semantic basis. `QueryStateStore` holds result references, execution checkpoints, fitted output suffixes, and publication claims under one entry, byte, and lifetime bound. Claims hide new allocations until the final hosted publication transaction commits; a rejected or canceled attempt releases its own work. Published execution pages release their consumed producer payload and replay exactly without repeating native effects, while concurrent use of an unfinished token reports a finite conflict. Transitive dependencies retain exact authority and ownership; unadvertised private allocations are released when the fitted page commits. It issues row handles tied to each retained result, and the result projection returns those handles with retained rows. A run sourced from a retained symbol or binding result seeds the service from proven rows, including an empty set, without rediscovery or re-description. Optional row IDs select original rows in order; a proper subset retains incomplete-selection qualification and cannot establish absence for excluded rows. Binding column names survive empty results and selection. Qualified positives remain usable with their original limitations and omissions. Restoration rejects unavailable, foreign-row, or stale-basis results. A requested retention can fail for capacity without erasing the current query output. The read-result action presents a bounded page from retained rows using a distinct result cursor; it does not invoke semantic providers. An admitted retained presentation window survives output fitting and suffix retention. When output retention fails, the useful fitting prefix keeps its upstream coverage and detailed evidence, and its result cursor advances only past emitted rows.

An exact-symbol output may request `SOURCE`. At its emit stage, the service calls the existing source port with the same exact selector and read authority, a file region, no entity enumeration, and a fixed five-line window on each side. Returned text keeps its normalized committed-text proof and one-based line range. Source rejection or withheld text qualifies the query with a finite item cause; output and checkpoint bytes account for returned text. This adds one source read per emitted symbol and does not alter discovery refinement.

Discovery candidates are refined to exact symbols before query output. Query-local refinement preserves repeated rows in their source order; an explicit `distinct_symbols` stage keeps the first row for each canonical identity when requested. Failed refinements remain visible as limitations. Relation and traversal child budgets are derived from remaining parent capacity.

Exact-name discovery uses the same work-bounded candidate grant as indexed-word
discovery: at most half the remaining work, with the other half reserved for exact
refinement. Candidate capacity is independent of the output row limit. The
existing interpreter retains discovered selections and drains them across pages
without repeating discovery or refinement. Genuine provider qualifications
survive the last page; fuzzy search keeps its ranked, bounded discovery policy.

Unranked source-owned `ALL_DECLARATIONS` uses the versioned `KOTLIN_FILE_SOURCE_V2` ordering: lexical admitted file path, then declaration start offset. An immutable source-root/VFS partition frontier retains unopened directories and files, plus a detached offset for the active file. Each successor advances an input revision; it never rebuilds the consumed file or declaration prefix. Native objects are reacquired inside the admitted read, and exact K2 refinement remains required before emission. Four supported declaration families are classes, functions, properties, and type aliases, including nested eligible descendants of excluded containers.

`SEARCH_TEXT` lowers to a typed indexed-word source with the same source-set,
directory, package and kind restrictions. It grants discovery at most half the
remaining work, reserving authority to refine each admitted owner candidate.
Candidate capacity follows that discovery grant rather than returned-row capacity,
so downstream filters can admit a later owner within the same work budget. Native
text discovery deduplicates containing owners and retains one verified exemplar
per owner. The normal exact-refinement stage binds the exemplar's authority,
file and declaration range to its exact selector before attaching it to a row.
Source projection, checkpoints, retained rows and identity composition preserve
lexical evidence and charge its bytes. Relation expansion does not attach that
evidence to a different destination owner. Native stops are terminal incomplete
discovery; already detached candidates can still be resumed through the existing
interpreter without repeating discovery or retaining live PSI.

The interpreter retains both discovery input and already discovered candidates. Downstream rejection or filtering drives the producer again until output capacity, resource exhaustion, complete discovery, or a precise blocked cause. Result capacity bounds accepted output rather than candidate discovery. An indivisible directory partition exceeding preparation or retained-byte capacity remains a finite blocked outcome. Direct library-inclusive ALL is explicitly unsupported; it cannot claim completeness over a source-only substitute. Ranked fuzzy-name discovery retains its separate bounded enumeration semantics.

Discovery observations retain the declared universe, admitted scope and restrictions, input progress, actual discovery/refinement counts, phase durations, and blocked causes. Their units remain distinct from final emitted-row counts. Reference production preserves the original native search scope and retains one complete, bounded inventory of detached native provider locators; successors restore only the unconsumed ordinal. An opaque inventory that cannot fit candidate, time, or retention authority terminates with its exact blocked evidence rather than advertising an unusable continuation. Target confirmations consume semantic work, and duplicate native providers at one confirmed range reuse the retained proof. File-scoped imports and aliases remain independent compiler-confirmed occurrences. Only proven declaration-owned occurrences enter declaration graph expansion.

Relation inventory checks its remaining allowance before entering native
preparation. An already-expired request cannot start a provider search. A native
call already in progress can still overrun a cooperative allowance; its qualified
coverage and recorded elapsed time remain authoritative.

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

The incremental scoped `ALL` path walks admitted source partitions without whole-workspace name or file-type inventory construction. Generated primary-constructor properties retain K2 property identity. Java reference endpoints retain compiler identity, and workspace expansion preserves original subject restrictions separately from destination admission. [Read-limit settings](../../docs/hosted-read-configuration.md) tune operational bounds while default logs preserve stages, outcomes and their effective values.

The current [public tool contracts](../contracts/public-tools.md) distinguish presentation identity from canonical operation identity. Eager `query_symbols` owns declaration discovery and pipelines through `query.run`; `check_diagnostics` owns `diagnostic.check`. Hosted admission retains each tool's schema identity and typed syntax. The connected provider selects an enrolled workspace, then uses shared existing-IDE preparation and read dispatch. Operation effects, budgets, reference authority and exhaustive outcomes remain with their existing owners.

Cheap scope and declaration-family constraints precede native collection. Mixed-family syntax issues one symbol discovery request with all requested kinds retained. Project-only fuzzy declarations use scoped Kotlin files, and exact searches select only requested short-name index families. Package PSI runs outside native index callbacks before candidate collection. Qualified partial results retain their limitations through exact refinement.

Hosted query projection issues compact exact-symbol handles before encoding.
The final byte guard accounts for actual serialized references and connections,
then retains remaining transport output in the same query-state owner as execution
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

Pipeline checkpoints retain a typed cumulative emitted-row count separately from
pending work and identity/dedup state. Counts advance only for final output rows,
including after downstream filtering and distinct stages. Hosted presentation
suffixes carry the original logical minimum separately from their remaining
items, so output paging cannot reduce that minimum. Retained-result reads look
up existing producer checkpoints; they never recreate an evicted or expired
checkpoint. An unavailable producer checkpoint becomes terminal upstream
incompleteness while detached output remains readable.

Returned native discovery pages are observed after the restartable read action
produces detached discovery evidence. Qualified pages count as returned pages;
rejected admission and cancellation do not manufacture a page. These diagnostic
counts are separate from public calls and semantic result cardinality.

## Representation impact

An admitted impact plan enters the existing pipeline as exact producer routes. `QueryImpactTasks` caches each detached native value-flow read by its exact site, queues every compiler and reviewed model arrival separately, and records rejected reads, unsupported flow, explicit scope exits, cycles and capacity cuts as typed path terminals. Native observations establish compiler transfers; reviewed models supply representation or boundary meaning. Neither the ledger nor presentation creates missing semantic facts.

The existing checkpoint retains these tasks, reads and paths. Finalization validates seed and branch conservation before producing value-path rows. The closure requires every required obligation to be discharged; finishing task execution alone does not permit complete semantic proof. Retained witness sections derive from that same immutable ledger and existing query-state lifetime. Changing epoch requires fresh recipe admission rather than revival of old handles.

Impact checkpoint admission shares one request-local storage visitor across the
actual plan, tasks, routes, reads, paths and ledger. It charges incoming references
and collection cells, counts shared immutable payloads once by JVM identity, and
counts equal detached copies separately. Prospective expansion reserves new route
wrappers and prefixes before scheduling. This is conservative quota arithmetic,
not heap measurement or semantic deduplication; the existing quotas and safety
factors remain. Ordinary pipeline accounting retains its existing policy.

`FINDINGS` is a retained witness projection with one record per original ledger path. Paths sharing a destination remain separate. The admitted ordinal points to the original path object; the retained presentation supplies its existing issued row ID before compact DTO construction. Compact rows keep current representation alternatives and ordered model provenance, finite terminal causes and boundary obligations. Compiler transfer payloads and full model history remain on the linked `VALUE_PATHS` row. The original accounting and unresolved closure accompany every page, so a smaller summary cannot strengthen the investigation or revive a stale basis.

A work stop before impact finalization can retain an empty, qualified evidence-only value-path snapshot. Unfinished routes and cached native reads remain owned by the exact existing execution checkpoint. The snapshot does not establish investigation closure. Only a finalized investigation supplies original path ordinals and witness sections; pending witness reads reject with `RESULT_FIELD_UNAVAILABLE`. Retained presentation leaves the upstream checkpoint available.

Requested targets do not schedule additional value-flow exploration. Finalization passes the independently admitted requested-site universe into the same ledger, which compares exact site identities with original producers, destinations and step endpoints. Each target retains reached original paths, exact same-domain exclusions, or an unproven relationship. The last outcome adds a required relationship obligation to the existing closure. Retained `SITE_ACCOUNTING` derives from these original outcomes without changing path order or creating a second interpreter.
