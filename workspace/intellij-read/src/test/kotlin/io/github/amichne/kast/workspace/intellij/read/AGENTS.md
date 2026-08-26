# Existing Project admission, signal, and read-epoch tests

This package proves the KVP-014 boundary without opening, importing, refreshing, or waiting for an
IntelliJ project.

- Use a dynamic `Project` proxy only as the opaque live handle retained by admission.
- Drive platform observations through `ExistingProjectObservationPort`; every rejection must prove
  the exact observed prefix and that later stages were not called.
- Keep the supported host tuple and canonical report bytes in the shared typed fixture.
- The positive test must prove that the admitted value exposes no public `Project` member.
- Compare the generated report as exact bytes. Do not add a second JSON model or parser here.
- KVP-015 characterization remains portable on Java 21: use pure two-sample fixtures and keep
  `EpochSignalApiContract` compile-only. Tests inspect its class resource; they must never load it.
- The epoch ledger owns exactly five ordered signals and eleven ordered cases. Every case has two
  samples. Derive Gradle-root, import-completion, and dumb-mode transitions from typed fixture
  state; do not manufacture transitions from case labels.
- The VFS storm represents one 1,000-event batch and one metadata-counter advance. Keep the
  compile-only listener and counter under an exact member-reference allowlist so they cannot gain
  scheduling, refresh, read-action, or EDT capabilities. Bind the listener and rename-event class
  bytes to their exact fingerprints so branch direction and two-path retention cannot drift behind
  an unchanged member set; exercise both inbound and outbound move and rename cases.
- Use `VirtualFileManager.VFS_CHANGES` as the VFS authority. IDEA 262's
  `VirtualFileManager.modificationCount` and `structureModificationCount` are constant-zero
  authorities and must remain explicitly rejected.
- Bind Gradle model movement to `ExternalProjectInfo.lastImportTimestamp` and
  `lastSuccessfulImportTimestamp`; do not invent start/finish property names.
- KVP-017 tests exercise the production source through portable supplied observations: stable and
  changed same-source states, cross-source incomparability, every finite failure, all five signal
  transitions, inbound/outbound VFS movement, the one-advance 1,000-event batch, the 4,096-event
  bound, and malformed/overlong paths. The class contract binds exact listener-local member sets,
  the IDEA read-action surface, and exact live-source fingerprints while rejecting stronger effects.
  `ReportedProjectReadEpochTest` consumes the generated report and compares it to the independently
  owned exact-byte resource before proving its thirteen ordered relations through product sources.
- KVP-017 compiled-byte, identity, report, and detachment proofs live under `epoch/`; its inventory
  covers every production class, including companions and listener/source lambdas.

Run:

```shell
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'
./gradlew :workspace:intellij-read:characterizeEpochNegative
./gradlew :workspace:intellij-read:characterizeEpoch
./gradlew :workspace:contract:test :workspace:intellij-read:test --tests '*ProjectReadEpochTest'
./gradlew :workspace:intellij-read:generateProjectReadEpochReport
```
