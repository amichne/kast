# Event-driven indexing research ledger

This ledger records the evidence used for the workspace transition design.
Repository paths refer to the implementation in this release line.

| Question | Evidence | Accepted decision |
| --- | --- | --- |
| Where is freshness owned? | `WorkspaceTransitionWorker` owns the five-minute timer and runs the coordinator. `WorkspaceTransitionCoordinator` owns refresh, reconciliation, verification, transactional publication, and retry. `IdeaIndexSemanticAdmission` owns the revision and manifest barrier. | Keep one transition worker and one admission authority. Events and the recovery timer wake that worker. |
| How are VFS changes observed? | JetBrains documents bulk VFS listeners and warns that refresh reports only loaded VFS content. The supported 2026.1.3 runtime exposes `VirtualFileManager.VFS_CHANGES_BG` with `BulkFileListenerBackgroundable`; `WorkspaceVfsEventObserver` uses that pair and exact-root filtering. [JetBrains VFS contract](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html), [listener inventory](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html) | Treat events as prompt invalidation only. Retain a periodic recovery probe that falls back to full recovery reconciliation because creation or deletion events can be missed for unloaded children. |
| Can a Git event establish workspace truth? | Git documents `post-checkout` only after checkout or switch, while a worktree also contains dirty state and per-worktree index and HEAD state. [Git hooks](https://git-scm.com/docs/githooks.html), [Git worktree](https://git-scm.com/docs/git-worktree.html) | Do not install or depend on hooks. Classify `.git` VFS traffic as wake signals. Exclude commit identity from WSID. |
| How does Kast avoid publishing during a streamed Git operation? | `GitWorktreeTransitionGuard` resolves exact-root common and per-worktree Git paths and checks transaction markers before and after slow phases and at commit admission. `GitWorktreeTransitionGuardTest` streams 1,000 files across multiple quiet periods and starts a checkout after preparation. | Treat an active Git transaction as a retry signal. Discard its candidate. Use transaction markers only as a publication guard, never as semantic identity. |
| What must happen after a build change? | Gradle states that IDE import uses the Tooling API to query project hierarchy, dependencies, and source directories. Kast already has `IdeaGradleProjectLoadBridge.refreshExternalGradleProject` and `GradleProjectImportBridge.awaitGradleModelSettlement`. [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) | A build-semantic signal must complete refresh and model settlement before reconciliation, or block. |
| How does event classification stay aligned with build identity? | `BuildSemanticInputPolicy` is shared by `BuildSemanticInputIdentityResolver` and `WorkspaceVfsSignalClassifier`. Tests cover Gradle and Groovy build logic, dependency locks, `local.properties`, generated-output exclusion, and compiler-generated source inclusion. | Keep one precise path policy for hashed build inputs and their wake signals. Do not classify generated build output as a new build-semantic input. |
| Can derived module output become its own semantic input? | IntelliJ's unfiltered project order enumerator includes module-source compiler output such as `build/classes/kotlin/main`. `BuildClasspathFingerprintResolverTest` proves the production resolver removes that output and retains a module-library artifact. | Exclude module-source order entries by provenance. Keep explicit libraries, SDKs, compiler plugins, and processor artifacts in classpath identity and event observation. |
| What is the compiler and classpath lifecycle? | `BuildClasspathFingerprintResolver` hashes stable classpath roots. `IdeaSemanticEnvironmentIdentityResolver` adds module, SDK, source-root, order-entry, class-root, Kotlin facet and compiler-plugin evidence. A resolved SDK contributes its type, canonical home, and version but not its IntelliJ presentation label; an unresolved binding retains its reference name and type. `IdeaJavaCompilerIdentityResolver` adds effective Java language level, ordered javac options, bytecode target, annotation-processing configuration, processor paths and their content, processors, and options. | Include the resolved semantic environment in both pre-pass and post-pass WSID capture. Preserve order where compiler behavior preserves order, canonicalize sets and maps, and exclude presentation-only SDK renames. |
| Can reads continue during a transition? | `WorkspaceSemanticGate` opens a read lease for the exact READY manifest and revision. `IdeaIndexSemanticAdmission` counts active readers. It withdraws READY before it waits for them to finish. `IdeaIndexSemanticAdmissionTest` covers manifest movement and reader draining. | Let an admitted read finish against one committed SQLite snapshot. Reject its result if the revision or manifest moves. |
| How are external mutations serialized? | `WorkspaceTransitionIngress` starts from READY, withdraws admission, waits for active readers, and grants one mutation permit. `WorkspaceTransitionIngressTest` proves that a mutation waits for a different stable published manifest before it returns. | Route every supported mutation through one permit. Publish new evidence before reporting mutation success. |
| Can a public graph read repair its own cache? | `coordinatedSemanticGraph` calls the cache-only public graph operation under a read lease. Missing work produces an internal incomplete outcome before any write. The coordinator requests `RecoveryAudit`; only `reconcileSemanticGraph` receives a reconciliation token. `NativeSemanticGraphAdmissionTest` covers this boundary. | Keep public graph reads free of writes. Let the transition worker perform all repair, then retry the read against a new manifest. |
| Is SQLite publication coherent? | `SqliteSourceIndexStoreState.beginWorkspaceWrite` starts one outer transaction before reconciliation. Existing mutation batches become savepoints. `WorkspaceGenerationStore.commit` writes `workspace_publication` before the same transaction commits. `WorkspaceGenerationStoreTest` proves candidate facts and publication are invisible together and discard rolls both back. SQLite documents atomic commit and rollback recovery. [SQLite atomic commit](https://sqlite.org/atomiccommit.html) | Use one `source-index.db` transaction as the workspace visibility boundary. Do not export or retain per-workspace database generations. |
| Can publication partly commit? | The publication row, source facts, graph facts, progress, source-index generation, and WSID revision share one SQLite transaction. `WorkspaceTransitionCoordinator` discards the transaction on phase failure, identity movement, event invalidation, retry, or cancellation. | Treat only a committed publication row whose identity matches `schema_version` as visible. Every pre-commit failure is rollback. |
| What does READY identify? | `IdeaIndexSemanticAdmission.Status.Ready` contains a `PublishedWorkspaceGenerationManifest`. `KastIndexerBackendRuntimeStatusTest` proves that `runtime/status` exposes that same value and exposes no manifest outside generation-backed READY. | Treat READY as admission to one exact published manifest, not as a boolean. |
| How does restart recover the workspace writer? | `IndexerServerRuntime` opens the same canonical database. SQLite journal recovery chooses the prior or newer complete transaction. `ensureSchema` regenerates owned tables when the schema version differs. `IndexerServerRuntimeTest` proves no filesystem-generation state is created. | Trust SQLite transaction recovery, not a copied workspace index. Regenerate mismatched schemas without a migration or legacy reader. |
| What is the Rust local-read boundary? | `PublishedWorkspaceDatabase` resolves the exact root's `cache/source-index.db`, reads `workspace_publication` with `schema_version` in one transaction, and validates the same row again after the operation. Rust unit and CLI integration tests cover invalid files, missing rows, mismatches, movement, and shared repository bases. | Give default Rust-local semantic operations one exact published database. Reject database, runtime, or publication-row movement. Keep explicit database paths as diagnostic overrides only. |
| How is missed-event recovery bounded? | `WorkspaceEventWakeup` keeps a set of typed signals. `KastIdeaProjectIndexing` waits 300,000 ms after READY. The worker withdraws admission and runs one VFS-only `RecoveryProbe`; unchanged evidence restores the exact manifest without import or publication, while drift or inspection failure schedules a full transition. `WorkspaceTransitionWorkerRecoveryAuditTest`, `WorkspaceTransitionWorkerRecoveryAuditConcurrencyTest`, and `RecoveryAuditAdmissionTest` cover no-op restoration, missed drift, reader draining, event invalidation, publication-inspection failure, and cancellation. `WorkspaceTransitionCoordinatorTest` proves event storms remain one pending signal. | Use prompt event wakeups, a 250 ms resettable quiet period, and a five-minute correctness audit. Do not repeat Gradle import or publication when the exact evidence is unchanged. |
| How is an unstable workspace handled? | `WorkspaceTransitionCoordinatorTest` changes identity without an event and injects an event during reconciliation. Neither candidate publishes. | Capture WSID before and after reconciliation. Any difference or revision change schedules a new audit and keeps admission closed. |
| Can preparation hide a missed change? | `WorkspaceTransitionCoordinatorTest` changes full WSID without an event while publication preparation is blocked. The coordinator recaptures WSID, rolls back the candidate, and does not call commit. | Verify full identity once more after preparation and immediately before the SQLite commit. |
| How long can public startup wait? | Initial reconciliation always requests a build-semantic pass. `WorkspaceTransitionRuntime` bounds Gradle refresh at five minutes. The Rust CLI fake-clock test shows the old 60-second wait rejects a candidate that becomes ready after that valid refresh, while the six-minute default admits it. | Give the public READY wait the full refresh bound plus one minute for startup, settlement, reconciliation, and publication. |
| Can a cached compiler-ready model establish restart readiness? | IntelliJ 2026.1.3 and the supported installed 2026.2 host expose a sticky `JpsProjectLoadingManager` callback. For a cache-backed model, IntelliJ completes it after applying real JPS storage. Fresh or discarded caches do not require that callback. `GradleProjectImportBridgeJpsBarrierTest` covers queued, immediate, and skipped completion. `GradleProjectBootstrapTest` covers an unlinked cached model. | Supplement post-startup settlement with the cache-gated JPS barrier. Admit cached compiler readiness only when the exact normalized Gradle root is linked. Otherwise, link or refresh the exact root and inspect the resulting model again. |

## Deterministic proof map

- External source edit through the production VFS subscriber, immediate DIRTY,
  and one edited READY generation:
  `WorkspaceEventDrivenIntegrationTest`.
- Real 1,000-file Git checkout, production VFS delivery, exact-root transition
  guard, bounded work, no intermediate publication, and one final coherent
  generation:
  `WorkspaceEventDrivenIntegrationTest`.
- Deterministic streamed-checkout and checkout-after-prepare rejection:
  `GitWorktreeTransitionGuardTest` and `WorkspaceTransitionWorkerBuildSemanticTest`.
- Bounded 10,000-event storm and no intermediate publication:
  `WorkspaceTransitionCoordinatorTest`.
- Moving reconciliation and different-commit identity:
  `WorkspaceTransitionCoordinatorTest` and
  `WorkspaceStateIdentityResolverTest`.
- Dirty and untracked participation:
  `WorkspaceStateIdentityResolverTest`.
- Gradle failure and prior generation retention:
  `WorkspaceTransitionCoordinatorTest`.
- Mixed-generation read rejection:
  `IdeaIndexSemanticAdmissionTest`.
- Read-lease draining and mutation permit exclusion:
  `IdeaIndexSemanticAdmissionTest` and `WorkspaceTransitionIngressTest`.
- Uncommitted-write invisibility, publication-row atomicity, rollback, and
  single-database restart behavior:
  `WorkspaceGenerationStoreTest`.
- Publication before READY and publication-failure blocking:
  `KastIdeaProjectIndexingRuntimeTest`.
- Commit invalidation and event-concurrent publication:
  `WorkspaceTransitionCoordinatorTest`.
- Recovery-audit admission, unchanged restoration, missed-event repair, and
  cancellation:
  `RecoveryAuditAdmissionTest`, `WorkspaceTransitionWorkerRecoveryAuditTest`,
  and `WorkspaceTransitionWorkerRecoveryAuditConcurrencyTest`.
- Stable SDK semantics across presentation-only renames, with home, version,
  and unresolved-binding sensitivity:
  `IdeaSdkSemanticIdentityResolverTest`.
- Cache-only public graph coordination:
  `NativeSemanticGraphAdmissionTest`.
- Exact runtime manifest reporting:
  `KastIndexerBackendRuntimeStatusTest`.
- Rust publication-row validation, published-path containment, and post-read
  revalidation:
  `cli-rs/src/semantics/published_workspace/tests.rs` and
  `cli-rs/tests/runtime/semantic_workspace_admission/`.
- Delayed initial Gradle-refresh admission:
  `cli-rs/src/execution/runtime/backend/indexer_authority/tests/mod.rs`.
- Cache-backed JPS application, late registration, fresh-cache bypass, and
  exact Gradle-link admission:
  `GradleProjectImportBridgeJpsBarrierTest` and
  `GradleProjectBootstrapTest`.

## Known platform constraint

VFS notification completeness depends on which directory content the VFS has
loaded. This is why event receipt cannot be proof of semantic completeness.
The post-refresh WSID comparison and the five-minute admission-barrier probe are
correctness mechanisms, not performance fallbacks. The probe escalates to a
full recovery transition whenever exact evidence cannot prove that the current
manifest remains valid.

## Pre-implementation released-runtime reproduction

On 2026-08-04, the installed `kast 0.21.3` runtime for this exact worktree
reported `READY`, `referenceIndexReady: true`, and 17 source modules. The new
`WorkspaceGenerationStore.kt` was still absent from `kast files`, which
reported `SOURCE_INDEX_PROGRESS_INCOMPLETE` and
`UNKNOWN_PROJECT_MODEL_OWNERSHIP`. A broad `kast refresh` then failed with
`CHANGED_FILE_EVIDENCE_INCOMPLETE`. An explicit refresh of the new source
completed at graph generation 771 with no diagnostics, after which
`kast symbol find WorkspaceGenerationStore` resolved the exact class and its
members.

This reproduction was design input from the released runtime before this
implementation. It is not proof of the new publication or read boundaries.
It shows why process readiness and reference-index readiness cannot replace
event invalidation, exact reconciliation, and verified generation publication.
