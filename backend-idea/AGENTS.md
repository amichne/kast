# IDEA Backend Guidelines

## Workspace inventory authority

`IdeaProjectModelWorkspaceFileInventory` owns complete raw `.kt` and `.kts`
candidate discovery for the IDEA host. Collect candidates through
`FileTypeIndex`, IDEA module source/content scopes, and the linked Gradle model;
never recursively walk the filesystem. Admit only canonically contained paths,
preserve every containing module owner, and keep source-only, script-only, and
mixed fingerprints independent.

`IdeaGradleProjectLoadBridge` is the narrow Java firewall around unstable
Gradle-plugin APIs. It owns direct and composite linked roots, root-module
associations, build-qualified project paths, and structured source-set roots.
Only model-proven data may populate `gradleProjects` or `gradleSourceSets`.
IDEA module names, path fragments, and source-root basenames are legacy
unproven labels and must never be promoted to build-qualified evidence.

Map indexing, unavailable model data, and unassociated linked roots to the
typed `WorkspaceProjectModelIncompleteReason` variants. Missing model evidence
must fail closed or remain explicitly unproven; it must not become an empty
complete inventory or inferred proof.

## Workspace paging and ownership

`IdeaWorkspaceFilePaging` owns reusable snapshot leases and single-use module
page state in the shared `analysis-api` continuation store. Opaque handles are
query-, workspace-, module-, kind-, and generation-bound. Sort and deduplicate
before slicing, validate generation before every page and final validation,
and reissue page ownership atomically. Continuation state must extend a
domain-specific `ContinuationOwnedState` and output a domain-specific
`ContinuationProjection`; never expose state through a generic wrapper.

`RunningAnalysisServer` is the only owner that closes `KastPluginBackend`.
Backend close must drain reference, diagnostic, snapshot, and page stores once,
continue cleanup after individual failures, and preserve the runtime order:
cancel indexing, close the running server/backend, then close the separately
owned source-index store.

## Relationship ownership

`backend-idea` owns compiler/PSI execution and all semantic relationship
continuation state for the IDEA runtime.

- `RelationshipContinuationStore` is the only reference/call/type semantic
  state owner. It adapts #338's generic `analysis-api`
  `ServerHeldContinuationStore`; its sealed state extends
  `ContinuationOwnedState`, its output extends `ContinuationProjection`, and
  its explicit disposer participates in the landed backend/server close
  lifecycle. Store pure canonical anchors, query/source/generation proof,
  bounded candidate/frontier/visited/provider positions, and returned-before
  counts; never retain PSI, smart pointers, or analysis-session objects.
- Shared-store consume is typed as retained exact-query, expired,
  query-mismatched, or absent. An absent canonical handle is always invalid
  `UNKNOWN_HANDLE`, including
  restart-to-fresh-backend, random UUID, replay, and eviction. Stale requires
  retained generation mismatch or retained expiry. Test backend-A to backend-B
  and random UUID equivalence with zero provider work.
- Invoke the shared store's consume/reissue transition inside one
  `timedReadAction`. Family/query validation, `PsiModificationTracker`
  generation comparison, target resolution, provider work, state mutation,
  and next-token publication happen under that read lock. `Complete` disposes;
  `Reissue` atomically moves the same owned state behind a fresh handle.
  `RunningAnalysisServer` is the single backend close owner, and
  `analysis-server` must not preflight generation or own another semantic
  store.
- `ObservedAnalysisBackend` explicitly delegates every handle-bearing method
  and records exactly one matching operation. Add delegation and queued-write
  race tests whenever the backend contract changes.
- Exact INDEX references query FQ name plus canonical target path and non-null
  target offset. Unsafe first-page index evidence may fall back to IDEA; an
  INDEX-bound continuation never switches sources.
- Bounded IDEA reference/incoming-call discovery streams
  `FileTypeIndex.processFiles` through cap plus one. Cap-plus-one stops
  enumeration and returns a typed family budget outcome with no records, page
  claim, or retained partial snapshot; at or below the cap, sort the complete
  buffer and use `PsiReferenceScanner` in lexical offset order. Direct
  inheritors use the same cap-plus-one rule around bounded
  `ClassInheritorsSearch.search(...).forEach` and sort only complete admitted
  canonical-anchor snapshots. Do not materialize
  `FileTypeIndex.getFiles(...)`, call `ReferencesSearch.findAll`, or call
  inheritor `findAll()`.
- Outgoing calls use resumable lexical DFS over the selected declaration body.
  Persist only a bounded root-to-current child-index stack and next-reference
  index. Traverse nested blocks, local property initializers, and lambda bodies;
  lambda-contained calls belong to the enclosing named callable because the
  relationship contract has no navigable lambda identity. Skip nested named
  function, class, object, and accessor bodies. Report `Exact` only after every
  owned node, including lambdas, is exhausted. Emit breadth-first edges by
  depth, canonical parent identity, canonical call-site file/start/end, then
  canonical related identity; never globally sort an unseen child set by
  related name. References sharing an exact call-site range are one local tie
  group sorted by related identity and charged to the candidate/state bound;
  overflow degrades without a partial group. Test reverse-related-name pages,
  lambda-only callees, and page resume inside a lambda without replay or PSI
  retention.
- After exact anchor verification, apply ADR 0022's command-family subject-kind
  matrix before provider/index work or state creation. Unsupported pairs return
  the owning response's `UNSUPPORTED_SUBJECT_KIND`; test every pair with a
  zero-work tripwire.

## Verification

Run `./gradlew :backend-idea:test` and the affected `:analysis-server:test`
contract tests. Relationship changes also require cap/cap-plus-one provider
tests, generation/write-race tests, `ObservedAnalysisBackend` delegation tests,
opaque continuation resume tests, absent-versus-stale classification tests,
outgoing lexical-DFS nested-declaration/page-resume tests, reverse-related-name
global page-order/no-overlap tests, lambda-only callee and lambda-resume tests,
and the complete subject-kind zero-work matrix.

Workspace inventory changes also require:

- `./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*IdeaWorkspaceFilePagingTest*' --tests '*IdeaGradleFileProvenanceTest*' --no-daemon`
- `./gradlew :backend-idea:test --tests '*KastPluginBackendContractTest*' --tests '*KastIdeaBackendRuntimeTest*' --no-daemon`
- Run Kast diagnostics for every materially edited Kotlin file after IDEA has
  reloaded the worktree.
