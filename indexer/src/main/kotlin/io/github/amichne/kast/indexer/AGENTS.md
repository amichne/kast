# Installed indexer host boundary

This package owns launcher admission, endpoint preparation and activation, wire framing, and the
IntelliJ application starter for one isolated installed indexer.

- Preparation may create canonical state and retire stale exact markers, but it must not bind a
  socket or publish readiness.
- Only a successfully constructed runtime may activate transport and publish the generated endpoint
  descriptor. The activated transport captures that runtime's dispatch capability.
- Keep launcher and wire failures finite typed data; project them to process exit or transport
  closure only at this package boundary.
- Fixed endpoint documents use explicit compiler-generated serialization factories.

Run `./gradlew :indexer:test --tests '*InstalledIndexerLaunchTest'`, then
`./gradlew :indexer:check :indexer:verifyPortableDistLayout`.
