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

## Paging and ownership

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

## Focused verification

- `./gradlew :backend-idea:test --tests '*IdeaWorkspaceFileInventoryTest*' --tests '*IdeaWorkspaceFilePagingTest*' --tests '*IdeaGradleFileProvenanceTest*' --no-daemon`
- `./gradlew :backend-idea:test --tests '*KastPluginBackendContractTest*' --tests '*KastIdeaBackendRuntimeTest*' --no-daemon`
- Run Kast diagnostics for every materially edited Kotlin file after IDEA has
  reloaded the worktree.
