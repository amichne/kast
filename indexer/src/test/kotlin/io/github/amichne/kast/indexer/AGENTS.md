# Indexer boundary tests

These tests prove the installed indexer's launcher, application-startup, transport, and plugin
registration contracts.

- Assert filesystem-visible readiness: rejected runtime construction leaves no socket or descriptor,
  and successful construction publishes a descriptor decodable by its generated serializer.
- Keep bootstrap tests independent of a foreground IDE and use exact temporary workspace/socket
  roots.
- Registration tests must inspect the packaged plugin metadata and Gradle Tooling service resource.

Run `./gradlew :indexer:test`, then `./gradlew :indexer:verifyPortableDistLayout`.
