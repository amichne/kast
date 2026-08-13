# Symbol contract module guide

`:symbol:contract` owns detached host-neutral request policy for symbol reads. It does not own
IntelliJ scopes, PSI, indexes, query execution, mutation authority, or transport.

## Module map

- `SymbolSearchScope.kt` owns the generation-bound symbol scope request, exact model owner, and
  authored/generated read policy.

## Dependency boundary

- Production exports only `:kernel` and `:workspace:contract`.
- Do not import IntelliJ, Gradle, JDBC, filesystem, process, transport, legacy backend, adapter, or
  service-locator types.
- Readability policy never grants edit, write, or mutation authority.

## Verification ladder

1. Run `./gradlew :symbol:contract:test --tests '*SourceRoot*PolicyTest'`.
2. Run `./gradlew :symbol:contract:test`.
3. Run direct IntelliJ adapter consumers after changing a public contract.
4. Run `./gradlew verifyKastArchitecture --configuration-cache`.
