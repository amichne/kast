# Symbol service module guide

`:symbol:service` owns generation admission for bounded symbol discovery and compiler-grounded
exact symbol resolution and description. It does not own live IntelliJ state, scope compilation,
index access, transport, native selector issuance, or mutation.

## Invariants

- Only the lease of the current `Ready(PublishedWorkspace)` can reach the compiler port.
- Compiler output must retain the request lease, scope, kind, and work bound.
- Exact resolution must start from a batch-owned selection, and compiler output must preserve its
  lease, scope, file, name, and offset.
- Exact description must start from `SymbolSelector`, revalidate under the current lease, and
  retain that same selector in the detached description.
- Scope, provider, and index failures remain closed rejection data.
- The service has no IntelliJ, Gradle, filesystem, persistence, transport, or write authority.

## Verification ladder

1. Run `./gradlew :symbol:service:test --tests '*SymbolDiscoveryServiceTest' --tests '*SymbolExactServiceTest'`.
2. Run `./gradlew :symbol:contract:test :symbol:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
