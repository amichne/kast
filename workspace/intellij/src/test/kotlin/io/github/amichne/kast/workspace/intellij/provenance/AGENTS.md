# Gradle source-root provenance tests

This directory proves producer-model capture, import transport, authored/generated provenance, and
source-root admission for the installed workspace adapter.

- Keep Tooling producer, service-loader, plugin descriptor, and installed capture expectations in
  one exact package-aware contract.
- Prove Gradle evidence is producer-owned and deterministic; guessed conventional directories are
  not valid source-root evidence.

Run `./gradlew :workspace:intellij:test --tests '*SourceRootProvenanceTest' --tests
'*InstalledGradleSourceRootCaptureTest' --tests '*GradleProducerProvenanceImportTest'`.
