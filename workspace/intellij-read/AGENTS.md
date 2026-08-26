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
- Expected failures remain the finite `ExistingProjectAdmissionFailure` hierarchy.
- Production compiles against the declared IDEA build 262 host and its bundled Kotlin/Gradle APIs.

## Focused proof

1. Run `./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'`.
2. Run `./gradlew :workspace:intellij-read:generateExistingProjectAdmissionReport :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'`.
3. Run `./gradlew :workspace:intellij-read:check verifyKastModuleGraph verifyForbiddenEffects`.
