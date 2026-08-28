# IDEA 262 project-read epoch

This reference records the KVP-017 production `ProjectReadEpoch` contract for IDEA build
`262.9437.185`. The generated JSON report at
`workspace/intellij-read/build/reports/KVP-017-read-epoch.json` is the executable proof artifact.

KVP-015 selected the supported platform signals. KVP-017 refines those signals into an opaque
identity that can be sampled before and after a semantic read. KVP-019 owns freshness admission;
KVP-022 owns result revalidation.

## Comparison domain

One private `ProjectReadEpoch.Source<State>` is retained by each admitted Project/runtime. The
source creates epochs and is also their comparison domain. Neither the source nor its
adapter-private `State` leaves `workspace:intellij-read`.

`Source` has a private constructor and only compiler-internal synthetic construction and
observation methods. The adapter compile is an explicit Kotlin friend of `workspace:contract`;
ordinary Kotlin and Java callers cannot create a source or a comparable epoch.

`relationTo` returns one closed relation:

- `SAME` means two immutable signal states came from the same source and are equal.
- `MOVED` means the same source observed different signal state.
- `INCOMPARABLE` means the epochs came from different Project/runtime sources.

The epoch has a private constructor and exposes no parser, raw counter, copy method, component
method, validity Boolean, source, listener, or callback. Comparing already-observed epochs does
not repeat platform observation or validation.

## Signal state

The adapter-private state contains the five ordered KVP-015 components:

| Component | Production evidence |
| --- | --- |
| `PROJECT_MODEL` | Project-scoped workspace-model counter, project root, cached Gradle root, and exact import timestamps |
| `PSI` | `PsiModificationTracker.modificationCount` |
| `ROOT_FILTERED_VFS` | One bounded project/runtime counter advanced once per in-root VFS event batch |
| `ROOT_MODEL` | `ProjectRootModificationTracker.modificationCount` |
| `DUMB_MODE_TRACKER` | `DumbService.modificationTracker.modificationCount` |

`DumbService.isDumb` is an admission gate. A sample taken while dumb is rejected; dumb state is
never stored as an epoch value. A smart-to-dumb-to-smart cycle changes the modification tracker,
so two smart endpoint samples still compare as `MOVED`.

Workspace-model and VFS subscriptions are installed once for the admitted Project/runtime
lifetime. The VFS listener retains both old and new paths for moves and renames, accepts at most
4,096 events in one batch, admits each path only within 4,096 characters and 8,192 UTF-8 bytes,
and advances its counter once when any event path is inside the exact root. Batch classification
is pure; the listener alone applies the counter effect. A 1,000-event in-root storm therefore
produces one metadata advance and no semantic job.

Cached Gradle selection inspects at most 16 cached Gradle models before it fails closed as
ambiguous. Epoch observation does not traverse repository files or VFS descendants.

## Observation boundary

`AdmittedIdeProject.observeReadEpoch()` exposes only `ProjectReadEpochObservation`. The live
source uses `ReadAction.computeCancellable`, rejects EDT entry, rechecks disposal, open and
initialized lifecycle, rejects dumb mode, and reads only cached constant-size metadata.

Expected failures are closed data for thread, lifecycle, root, cached Gradle-model, import-
timestamp, VFS bound/path, signal-exhaustion, preemption, and exact observation-stage failures.
`ReadAction.CannotReadException` becomes `ReadPreempted`. Other
`ProcessCanceledException` instances continue to propagate as cancellation.

## Proven cases

The generated report records two samples for stable state, workspace-model movement, Gradle
import start and completion, Gradle-root movement, PSI movement, VFS movement, root-model
movement, smart-dumb-smart movement, combined movement, and the 1,000-event VFS storm. Stable
state is `SAME`; every movement case is `MOVED`. Equal state from another Project or runtime is
`INCOMPARABLE`.

The report also records zero primitive-counter escapes, caller epoch reconstruction, repeated
validation, dumb-mode epoch values, VFS refreshes, Gradle imports or repairs, repository walks,
VFS traversals, source hashes, semantic jobs, EDT semantic work, blocking waits, and live-object
escapes.

## Focused proof

```shell
./gradlew :workspace:contract:test --tests "*ProjectReadEpochNegativeTest"
./gradlew :workspace:contract:test :workspace:intellij-read:test \
  --tests "*ProjectReadEpochTest"
./gradlew verifyKVP017CompletionReceipt
```
