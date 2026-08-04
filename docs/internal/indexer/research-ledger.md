# Event-driven indexing research ledger

This ledger records the evidence used for the workspace transition design.
Repository paths refer to the implementation in this release line.

| Question | Evidence | Accepted decision |
| --- | --- | --- |
| Where is freshness owned? | `KastIdeaProjectIndexing.runIndexing` owns refresh, reconciliation, verification, publication, retry, and the five-minute audit. `IdeaIndexSemanticAdmission` owns the revision barrier. | Keep one worker and one admission authority. Events wake that worker. |
| How are VFS changes observed? | JetBrains documents bulk VFS listeners and warns that refresh reports only loaded VFS content. The supported 2026.1.3 runtime exposes `VirtualFileManager.VFS_CHANGES_BG` with `BulkFileListenerBackgroundable`; `WorkspaceVfsEventObserver` uses that pair and exact-root filtering. [JetBrains VFS contract](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html), [listener inventory](https://plugins.jetbrains.com/docs/intellij/intellij-platform-extension-point-list.html) | Treat events as prompt invalidation only. Retain recovery reconciliation because creation or deletion events can be missed for unloaded children. |
| Can a Git event establish workspace truth? | Git documents `post-checkout` only after checkout or switch, while a worktree also contains dirty state and per-worktree index and HEAD state. [Git hooks](https://git-scm.com/docs/githooks.html), [Git worktree](https://git-scm.com/docs/git-worktree.html) | Do not install or depend on hooks. Classify `.git` VFS traffic as wake signals. Exclude commit identity from WSID. |
| What must happen after a build change? | Gradle states that IDE import uses the Tooling API to query project hierarchy, dependencies, and source directories. Kast already has `IdeaGradleProjectLoadBridge.refreshExternalGradleProject` and `GradleProjectImportBridge.awaitGradleModelSettlement`. [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html) | A build-semantic signal must complete refresh and model settlement before reconciliation, or block. |
| What is the classpath lifecycle? | `BuildClasspathFingerprintResolver` hashes stable classpath roots. `IdeaSemanticEnvironmentIdentityResolver` adds module, SDK, source-root, order-entry, and class-root evidence after refresh. | Include the resolved semantic environment in both pre-pass and post-pass WSID capture. |
| Can reads continue during a transition? | `KastIndexerBackend.currentWorkspace` opens an admission revision, runs one operation, and rechecks the revision. `IdeaIndexSemanticAdmissionTest` invalidates an in-flight read token. | Fail closed outside READY and reject a result if the revision moves before return. |
| Is SQLite export coherent? | SQLite specifies that `VACUUM INTO` creates a transactionally consistent snapshot, but an interrupted output can be incomplete. [SQLite VACUUM INTO](https://sqlite.org/lang_vacuum.html#vacuum_with_an_into_clause) | Export while holding the writer lock, check generation before and after, then make the completed database immutable. Never point at the export path directly. |
| What is the crash visibility boundary? | `WorkspaceGenerationStoreTest` interrupts after the immutable database move and before pointer replacement. The previous `current.json` and database remain current. SQLite documents its use of flush operations for durable commit. [SQLite atomic commit](https://sqlite.org/atomiccommit.html) | Sync the database and manifest, move both atomically, and replace the pointer last. Preserve previous generations. |
| How is missed-event recovery bounded? | `WorkspaceEventWakeup` keeps a set of typed signals. `KastIdeaProjectIndexing` waits 300,000 ms after READY, then runs a recovery-audit pass. `WorkspaceTransitionCoordinatorTest` proves event storms remain one pending signal. | Use prompt event wakeups, a 250 ms resettable quiet period, and a five-minute correctness audit. |
| How is an unstable workspace handled? | `WorkspaceTransitionCoordinatorTest` changes identity without an event and injects an event during reconciliation. Neither candidate publishes. | Capture WSID before and after reconciliation. Any difference or revision change schedules a new audit and keeps admission closed. |

## Deterministic proof map

- Source edit and checkout signals:
  `WorkspaceVfsSignalClassifierTest`.
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
- Crash-safe candidate invisibility:
  `WorkspaceGenerationStoreTest`.
- Publication before READY and publication failure blocking:
  `IndexerServerRuntimeTest`.

## Known platform constraint

VFS notification completeness depends on which directory content the VFS has
loaded. This is why event receipt cannot be proof of semantic completeness.
The post-refresh WSID comparison and the five-minute audit are correctness
mechanisms, not performance fallbacks.

## Released-runtime dogfood reproduction

On 2026-08-04, the installed `kast 0.21.3` runtime for this exact worktree
reported `READY`, `referenceIndexReady: true`, and 17 source modules. The new
`WorkspaceGenerationStore.kt` was still absent from `kast files`, which
reported `SOURCE_INDEX_PROGRESS_INCOMPLETE` and
`UNKNOWN_PROJECT_MODEL_OWNERSHIP`. A broad `kast refresh` then failed with
`CHANGED_FILE_EVIDENCE_INCOMPLETE`. An explicit refresh of the new source
completed at graph generation 771 with no diagnostics, after which
`kast symbol find WorkspaceGenerationStore` resolved the exact class and its
members.

This reproduction shows why process readiness and reference-index readiness
cannot replace event invalidation, exact reconciliation, and verified
generation publication.
