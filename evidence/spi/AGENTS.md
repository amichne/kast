# Evidence SPI module guide

`:evidence:spi` owns host-neutral capabilities for inspecting, preparing, committing, and
discarding workspace publication. Implementations own persistence and transaction effects.

## Invariants

- Publication is one begin, prepare, commit-or-discard protocol.
- Every capability remains bound to its implementation owner.
- The SPI exposes detached publication evidence, never JDBC or store handles.
- Do not import JDBC, SQLite, IntelliJ, Gradle, filesystem, transport, server, or legacy hosts.

## Verification ladder

1. Run `./gradlew :evidence:contract:test :evidence:spi:test`.
2. Run `./gradlew :workspace:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
