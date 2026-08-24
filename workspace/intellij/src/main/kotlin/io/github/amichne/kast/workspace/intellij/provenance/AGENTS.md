# Gradle source-root provenance guide

This package owns the Gradle Tooling model, IntelliJ import bridge, and exact-path authority that
prove whether each imported source root is authored or generated.

## Invariants

- Provenance comes only from exact Gradle producer outputs or an explicit generated source type.
- Missing and conflicting producer evidence remain closed typed failures or `UNKNOWN` contract
  evidence; ordinary source types never manufacture authored provenance.
- Gradle-side model values cross the Tooling API boundary as immutable typed documents.
- The resolver extension retains producer evidence before IntelliJ content-root projection, and
  installed capture detaches all live external-system model values before returning.
- The service provider, IntelliJ plugin registration, and packaged-class requirements must use
  this package's exact implementation names.

## Verification ladder

1. Run `./gradlew :workspace:intellij:test --tests '*SourceRootProvenanceTest' --tests '*InstalledGradleSourceRootCaptureTest' --tests '*GradleProducerProvenanceImportTest'`.
2. Run `./gradlew :workspace:intellij:test`.
3. Run `./gradlew :indexer:test --tests '*GradleProvenancePluginRegistrationTest'`.
