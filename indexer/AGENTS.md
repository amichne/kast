# Indexer module guide

`:indexer` is the isolated outer host for one already-constructed target runtime composition.

## Dependency boundary

- Production has exactly one project dependency: `:runtime:composition`.
- Do not import target contracts, services, adapters, runtime-server types, legacy aggregates,
  compatibility routes, fallbacks, service locators, JDBC, or transport implementation details.
- Construction of the complete implementation graph belongs only to `:runtime:composition`.
- Installed-product bootstrap, IntelliJ packaging, and process admission belong to KCS-021.

## Contract invariants

- The host consumes only the composition-owned dispatch capability.
- Endpoint preparation may create only canonical persistent state and retire stale exact markers.
  Bind the socket and publish its generated endpoint descriptor only after runtime composition
  succeeds, and capture that created dispatch in the activated transport. Socket reachability must
  therefore prove a dispatch-capable runtime, never an in-progress startup.
- Each indexer process receives endpoint-local IntelliJ config, system, and log directories;
  durable workspace evidence remains in the endpoint's SQLite siblings across process restarts.
- The packaged plugin descriptor registers both the indexer application starter and the workspace
  adapter's Gradle project-resolve extension; required-entry verification must keep the resolver,
  Tooling model, and producer-evidence types in the installed plugin archive.
- Raw request documents enter at one outer host frame and immediately refine to the closed
  composition dispatch result.
- No aggregate backend or duplicate semantic authority exists in this module.

## Verification ladder

1. Run `./gradlew :indexer:test --tests '*KastIndexerHostTest'`.
2. Run `./gradlew :indexer:test verifyKastModuleGraph`.
3. Run `./gradlew verifyNoLegacyArchitecture verifyKastModuleGraph build` for the full cutover.
