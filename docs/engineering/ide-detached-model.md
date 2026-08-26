# IDEA 262 detached-project model

This reference records the KVP-016 detached-model contract for IDEA build `262.9437.185` and the
exact source revision admitted by `KVP-016-COMPLETE`. The generated report at
`workspace/intellij-read/build/reports/KVP-016-detached-model.json` is the executable artifact.
The completion receipt at `build/reports/delivery/receipts/KVP-016-COMPLETE.receipt.json` binds
that artifact and its source inputs to one Git revision.

The generated report and the completion receipt are stronger authorities than this page. A later
revision must regenerate and re-admit them before it can claim the same result.

## Capture boundary

`AdmittedIdeProject.captureDetachedModel()` keeps the admitted IntelliJ `Project` private. It
delegates live observation to `LiveDetachedModelCapture`, which uses IDEA 262
`ReadAction.computeCancellable`. The report names this mode `CANCELLABLE_WRITE_PRIORITY_READ`.

The adapter rejects an EDT caller before it starts the read. Inside the read, it checks
cancellation, project disposal, open state, initialization, dumb mode, the canonical root, and
cached Gradle model completeness. `ReadAction.CannotReadException` becomes `READ_PREEMPTED`. A
`ProcessCanceledException` remains cancellation and leaves the adapter.

The Gradle observation reads only cached `ExternalProjectInfo` values from `ProjectDataManager`.
It requires one complete exact-root model and does not start, link, import, prepare, or repair a
Gradle project. Module, source-root, SDK, and classpath values become primitive boundary data
inside the read. Pure refinement then constructs `DetachedIdeWorkspaceModel`.

The captured model sorts modules by name. It sorts source roots by workspace-relative location
and classpath entries by exact URL. Every retained list is a defensive JVM-unmodifiable copy.

## Fixed bounds

KVP-016 rejects an observation that exceeds any bound. It does not truncate the observation.

| Observed value | Maximum |
| --- | ---: |
| Cached Gradle models | 8 |
| Modules | 128 |
| Source roots per module | 256 |
| Classpath entries per module | 512 |
| Identity text | 512 UTF-8 bytes |
| Path identity | 4,096 UTF-8 bytes |
| Classpath URL identity | 8,192 UTF-8 bytes |

IDEA 262 exposes one stoppable `OrderEnumerator.forEach(Processor)` over order entries. It exposes
the roots of one entry only as an array. Capture therefore requests one entry at a time, retains at
most 512 roots, and stops both the current root loop and the outer processor when it observes root
513. It never calls the whole-classpath `OrderRootsEnumerator.getUrls`, `getRoots`, or
`getRootEntries` materializers.

The text refiners also cap the character count at the same numeric ceiling. Character and UTF-8
bounds are checked before blank, trim, control, or syntax scans, so oversized observations reject
without unbounded semantic work. The refiners reject malformed, outside-root, duplicate,
conflicting, and ambiguous values through the closed `DetachedModelCaptureFailure` set.

## Detached authority

The report fixes `schemaVersion` at `1`, `authority` at `OPEN_PROJECT`, `canonicalRoot` at
`/workspace/kast`, and `modelState` at `COMPLETE_DETACHED`.

The report retains these facets in order:

1. `ROOT`
2. `MODULES`
3. `SOURCE_ROOTS`
4. `GRADLE_OWNERSHIP`
5. `SDK`
6. `CLASSPATH_IDENTITY`
7. `HOST_COMPATIBILITY`

The detached model rejects these live capabilities in order:

1. `PROJECT`
2. `VIRTUAL_FILE`
3. `MODULE`
4. `SEARCH_SCOPE`
5. `PSI`
6. `GRADLE_DATA_NODE`
7. `CALLBACK`
8. `MUTABLE_COLLECTION`

KVP-016 does not define a production epoch field or freshness rule. The report records
`productionEpochFieldCount` as zero. KVP-017 owns `ProjectReadEpoch` and the comparison that
admits or rejects model freshness.

## Forbidden-effect observations

The canonical report records zero for each stronger operation:

- `gradleImportCount`
- `gradleLinkCount`
- `gradlePrepareCount`
- `gradleRepairCount`
- `vfsRefreshCount`
- `repositoryWalkCount`
- `sourceHashCount`
- `blockingWaitCount`
- `liveObjectEscapeCount`
- `edtSemanticWorkCount`

The live adapter performs no VFS refresh, repository walk, source read, source hash, blocking
wait, Gradle state change, live-object escape, or semantic work on the EDT. Cancellation checks
occur during the cached-model, module, source-root, and classpath loops.

## Focused proof

The negative selector proves that malformed, incomplete, ambiguous, moved, or oversized
observations fail closed:

```shell
./gradlew :workspace:intellij-read:test --tests "*DetachedModelNegativeTest"
```

The positive selector generates the canonical report and proves exact values, deterministic
ordering, defensive copies, public API detachment, and exact report bytes:

```shell
./gradlew :workspace:intellij-read:test --tests "*DetachedModelTest"
```
