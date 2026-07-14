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

- `RelationshipContinuationStore` owns call, implementation, and hierarchy
  pages. References continue to use the dedicated source-aware continuation
  state landed by #337. Both compose #338's generic `analysis-api`
  `ServerHeldContinuationStore` and participate in backend/server close.
- Call and type relationship state contains only the normalized query, the
  semantic generation, returned-before proof, and at most 16,384 canonical
  result records. Never retain PSI, smart pointers, or analysis-session
  objects. A first request computes one complete bounded snapshot; later pages
  consume that snapshot without repeating provider work.
- Shared-store consume is typed as retained exact-query, expired,
  query-mismatched, or absent. An absent canonical handle is always invalid
  `UNKNOWN_HANDLE`, including
  restart-to-fresh-backend, random UUID, replay, and eviction. Stale requires
  retained generation mismatch or retained expiry. Test backend-A to backend-B
  and random UUID equivalence with zero provider work.
- Capture the PSI generation before snapshot collection. Commit a successful
  snapshot only inside `timedReadAction` after proving the generation is
  unchanged, and consume/reissue every later page inside `timedReadAction` so
  generation comparison and next-token publication are atomic with respect to
  writes. `Complete` disposes; `Reissue` moves the same owned state behind a
  fresh single-use handle. `RunningAnalysisServer` is the single backend close
  owner, and `analysis-server` must not own another semantic store.
- `ObservedAnalysisBackend` explicitly delegates every handle-bearing method
  and records exactly one matching operation. Add delegation and queued-write
  race tests whenever the backend contract changes.
- Exact INDEX references query FQ name plus canonical target path and non-null
  target offset. Unsafe first-page index evidence may fall back to IDEA; an
  INDEX-bound continuation never switches sources.
- Compiler relationship snapshots use the existing call/type engines with the
  16,384-record state ceiling as their explicit result and candidate budget.
  Any timeout, engine truncation, or cap overflow returns a typed budget
  limitation before a page or continuation is issued. Only a complete admitted
  snapshot is sorted and paged; no partial snapshot is retained.
- After exact anchor verification, apply ADR 0022's command-family subject-kind
  matrix before provider/index work or state creation. Unsupported pairs return
  the owning response's `UNSUPPORTED_SUBJECT_KIND`; test every pair with a
  zero-work tripwire.

## Verification

Run `./gradlew :backend-idea:test` and the affected `:analysis-server:test`
contract tests. Relationship changes also require state-cap rejection,
generation validation, `ObservedAnalysisBackend` delegation, opaque
continuation resume, absent-versus-stale classification, deterministic
no-overlap paging, and the complete subject-kind zero-work matrix.

Workspace inventory changes also require:

- `./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*IdeaWorkspaceFilePagingTest*' --tests '*IdeaGradleFileProvenanceTest*' --no-daemon`
- `./gradlew :backend-idea:test --tests '*KastPluginBackendContractTest*' --tests '*KastIdeaBackendRuntimeTest*' --no-daemon`
- Run Kast diagnostics for every materially edited Kotlin file after IDEA has
  reloaded the worktree.
