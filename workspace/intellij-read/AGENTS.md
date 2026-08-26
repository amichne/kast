# Workspace IntelliJ read adapter guide

`:workspace:intellij-read` owns admission and bounded observation of the one IntelliJ `Project`
already supplied by the hosted plugin. It is an `IDE_READ_ONLY` module and depends only on
`:protocol:contract` and `:workspace:contract`.

## Invariants

- `AdmittedIdeProject` retains the live `Project` privately; consumers receive detached root and
  compatibility proof and cannot recover generic IntelliJ authority.
- Admission checks lifecycle before root, cached Gradle model, dumb mode, Kotlin mode, and host
  compatibility. A failed earlier check prevents every later probe.
- Gradle model reads observe only cached `ExternalProjectInfo`. Do not call data preparation,
  linking, refresh, import, or any wait API.
- Project roots are compared as already-normalized detached identities. Admission performs no VFS
  refresh, filesystem traversal, source hashing, or repair.
- KVP-015 characterizes five epoch-signal categories without defining the production epoch type:
  project model, PSI, root-filtered VFS events, root model, and dumb mode. IDEA 262's
  `VirtualFileManager` modification counts are constant zero and are prohibited as epoch evidence.
- A VFS event observer may increment one bounded project/runtime metadata counter. It must not
  schedule semantic work, dispatch to the EDT, refresh VFS, or traverse event descendants.
- Expected failures remain the finite `ExistingProjectAdmissionFailure` hierarchy.
- KVP-016 captures root, modules, source roots, Gradle ownership, SDK, classpath URLs, and host
  compatibility into `DetachedIdeWorkspaceModel`. Every retained collection is defensively
  unmodifiable and every identity is bounded before it enters the model.
- Live detached capture rejects EDT entry before `ReadAction.computeCancellable`, then rechecks
  disposal, open state, initialization, smart state, exact root, and bounded cached Gradle
  completeness inside the read.
  KVP-017 solely owns production epoch identity and freshness.
- Production compiles against the declared IDEA build 262 host and its bundled Kotlin/Gradle APIs.

## Focused proof

1. Run `./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'`.
2. Run `./gradlew :workspace:intellij-read:generateExistingProjectAdmissionReport :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'`.
3. Run `./gradlew :workspace:intellij-read:check verifyKastModuleGraph verifyForbiddenEffects`.
4. Run `./gradlew :workspace:intellij-read:characterizeEpochNegative`.
5. Run `./gradlew :workspace:intellij-read:characterizeEpoch`.
6. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelNegativeTest'`.
7. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelTest'`.
8. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelClassContractTest'`.
