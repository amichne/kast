# Indexer plugin registration

This directory owns the single canonical descriptor source used by indexer registration tests.
`:ide-plugin` owns copying that descriptor into delivered plugin archives.

- Register only the isolated Kast application starter and the exact workspace Gradle resolver.
- Keep implementation class names synchronized with `:ide-plugin`'s packaged descriptor, runtime
  entries, and registration tests; a foreground IDE project is never part of startup.

Run `./gradlew :indexer:test --tests '*GradleProvenancePluginRegistrationTest'`, then
`./gradlew :indexer:verifyPortableDistLayout`.
