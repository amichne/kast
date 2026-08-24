# Indexer plugin registration

This directory owns the installed indexer's IntelliJ plugin descriptor.

- Register only the isolated Kast application starter and the exact workspace Gradle resolver.
- Keep implementation class names synchronized with packaged runtime entries and registration
  tests; a foreground IDE project is never part of startup.

Run `./gradlew :indexer:test --tests '*GradleProvenancePluginRegistrationTest'`, then
`./gradlew :indexer:verifyPortableDistLayout`.
