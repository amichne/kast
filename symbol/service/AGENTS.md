# Symbol service module guide

`:symbol:service` owns generation admission for bounded symbol discovery. It does not own live
IntelliJ state, scope compilation, index access, transport, exact selection, or mutation.

## Invariants

- Only the lease of the current `Ready(PublishedWorkspace)` can reach the compiler port.
- Compiler output must retain the request lease, scope, kind, and work bound.
- Scope, provider, and index failures remain closed rejection data.
- The service has no IntelliJ, Gradle, filesystem, persistence, transport, or write authority.

## Verification ladder

1. Run `./gradlew :symbol:service:test --tests '*SymbolDiscoveryServiceTest'`.
2. Run `./gradlew :symbol:contract:test :symbol:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
