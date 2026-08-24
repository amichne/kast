# Gradle Tooling service registration

This directory owns Java service-loader registration for the detached Gradle source-root producer.

- The provider entry must name the exact `ModelBuilderService` implementation packaged by
  `:workspace:intellij`.
- Update the service entry, plugin registration, packaged-class checks, and provenance import tests
  together when the producer moves.

Run `./gradlew :workspace:intellij:test --tests '*GradleProducerProvenanceImportTest'`, then
`./gradlew :indexer:test --tests '*GradleProvenancePluginRegistrationTest'`.
