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
- KVP-017 gives each admitted Project/runtime one private `ProjectReadEpoch.Source`. It installs
  one project-lifetime workspace-model listener and one root-filtered VFS listener; raw platform
  counters and state remain adapter-private.
- The module's explicit Kotlin friend path is the sole construction route to the contract-internal
  epoch source. Its constructor is private and its internal methods are JVM-synthetic; do not
  expose or replace this edge with a public factory.
- Live epoch observation rejects EDT entry, runs one short cancellable read, rechecks lifecycle and
  dumb mode, and returns only an opaque epoch or a finite failure. Same-source equal state is
  `SAME`, changed state is `MOVED`, and another admitted Project/runtime is `INCOMPARABLE`.
- One VFS batch may contain at most 4,096 events; paths are bounded before parsing to 4,096
  characters and 8,192 UTF-8 bytes. Pure batch classification precedes the sole counter effect.
  Observation never refreshes, imports, traverses, hashes, schedules semantic work, or waits.
  KVP-019 owns later freshness policy; KVP-017 owns only observation and comparison.
- Production compiles against the declared IDEA build 262 host and its bundled Kotlin/Gradle APIs.
- KVP-018 recursively inventories every compiled main class, scans the same admitted bytes, and
  admits only the exact runtime project closure `:kernel`, `:protocol:contract`, and
  `:workspace:contract`. It also binds the resolved Kotlin/annotation runtime artifacts by exact
  coordinate, name, and SHA-256 with a separate stronger-effect scan. Its report binds all-zero
  effects and semantic KVP-016/KVP-017 receipt digests; no compiled-class allowlist may substitute
  for the complete inventory.

## Focused proof

1. Run `./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'`.
2. Run `./gradlew :workspace:intellij-read:generateExistingProjectAdmissionReport :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'`.
3. Run `./gradlew :workspace:intellij-read:check verifyKastModuleGraph verifyForbiddenEffects`.
4. Run `./gradlew :workspace:intellij-read:characterizeEpochNegative`.
5. Run `./gradlew :workspace:intellij-read:characterizeEpoch`.
6. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelNegativeTest'`.
7. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelTest'`.
8. Run `./gradlew :workspace:intellij-read:test --tests '*DetachedModelClassContractTest'`.
9. Run `./gradlew :workspace:contract:test :workspace:intellij-read:test --tests '*ProjectReadEpochTest'`.
10. Run `./gradlew :workspace:intellij-read:generateProjectReadEpochReport`.
11. Run `./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalkNegative`.
12. Run `./gradlew :workspace:intellij-read:verifyNoHostedRepositoryWalk`.
