# IDEA 262 epoch-signal ledger

This reference records the KVP-015 characterization for IDEA build `262.9437.185`. The generated
JSON report at `workspace/intellij-read/build/reports/KVP-015-epoch-ledger.json` is the executable
proof artifact.

KVP-015 selects observable signals. KVP-017 owns the production `ProjectReadEpoch` type and its
comparison rules.

## Signal set

The ordered ledger contains five signal categories.

| Signal | IDEA 262 authority | Scope and rule |
| --- | --- | --- |
| `PROJECT_MODEL` | `WorkspaceModelTopics.CHANGED` plus exact-root `ExternalProjectInfo` import timestamps | A project-scoped metadata counter advances on a workspace-model change. Import start, completion, and root movement also change the observation. |
| `PSI` | `PsiModificationTracker.modificationCount` | The project PSI count detects parsed and semantic model movement. |
| `VFS` | `VirtualFileManager.VFS_CHANGES` | A root-filtered metadata counter advances after an in-root event batch. The listener does not run semantic work. |
| `ROOT_MODEL` | `ProjectRootModificationTracker.modificationCount` | The project root-model count detects module, source-root, SDK, and dependency-root movement. |
| `DUMB_MODE` | `DumbService.modificationTracker` plus `DumbService.isDumb` | The count detects a smart-to-dumb-to-smart cycle between samples. The state rejects a sample taken while the project is dumb. |

Project disposal, project closure, and cancellation are rejection channels. They are not epoch
scalars.

## Rejected VFS counters

IDEA 262 implements both `VirtualFileManagerImpl.getModificationCount()` and
`getStructureModificationCount()` as constant zero. KVP-015 rejects both APIs as epoch signals.
The `ManagingFS` filesystem count is test-only and is not a production alternative.

The `VFS_CHANGES` listener filters events to the admitted canonical root. An event batch performs
one bounded path comparison per event. It does not refresh VFS, enumerate descendants, or read
file content. Move and rename events refine both `getOldPath()` and `getNewPath()` so inbound and
outbound movement cannot disappear when the post-event path is outside the root.

## Movement cases

Each case uses exactly two samples. The characterization covers stable state, every single signal,
combined movement, import start and completion, Gradle-root movement, a smart-to-dumb-to-smart
cycle, and a 1,000-event VFS storm. Every movement changes at least one signal. Stable state changes
none.

The import-completion case holds `lastImportTimestamp` steady while
`lastSuccessfulImportTimestamp` catches up. The Gradle-root case changes a typed root identity as
well as the workspace-model counter. The dumb-mode case derives both transitions from an observed
`SMART, DUMB, SMART` timeline while the two read-epoch endpoints remain smart.

The VFS storm presents 1,000 events in one batch and advances the root-filtered metadata counter
once. An exact class-member allowlist keeps the listener limited to event-path projection and that
single counter operation. The allowlist continues through the outer API contract, metadata
counter, and root predicate. Those classes have no scheduling, refresh, read-action, filesystem
walk, hashing, or EDT capability.
Exact compiled-class fingerprints additionally bind the listener's rename branch direction and the
rename event's retention of both paths. Symmetric inbound and outbound move and rename fixtures
prove that either endpoint inside the root advances the counter.
The case therefore records zero semantic jobs and zero semantic work on the EDT.

## Forbidden observations

The canonical report records zero for each forbidden operation:

- VFS refresh
- Gradle import or repair
- recursive repository or VFS traversal
- source-content hashing
- semantic job scheduling from an event listener
- semantic work on the EDT
- blocking wait

`./gradlew :workspace:intellij-read:characterizeEpochNegative` rejects a ledger that omits a
signal or movement case, selects a constant-zero VFS API, records a forbidden operation, changes
the sample bound, duplicates an entry, or emits non-canonical JSON.

`./gradlew :workspace:intellij-read:characterizeEpoch` compiles the exact IDEA 262 API probe,
checks every movement fixture, and admits the canonical generated report.
