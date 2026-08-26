# Existing Project admission and epoch-signal tests

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

Run:

```shell
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'
./gradlew :workspace:intellij-read:characterizeEpochNegative
./gradlew :workspace:intellij-read:characterizeEpoch
```
