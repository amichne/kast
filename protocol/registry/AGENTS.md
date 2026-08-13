# Protocol registry module guide

`:protocol:registry` owns immutable, host-neutral definitions of public operations. It classifies
each permanent operation identity by typed input and semantic outcome, required capability, effect,
cost, scope, finite resource budget, and completeness policy.

## Module map

- `OperationClassification.kt` owns the closed effect, cost, scope, and completeness axes.
- `OperationBlocker.kt` owns common closed reasons that prevent an operation from running.
- `OperationDefinition.kt` binds typed operation metadata to generation-bound kernel outcomes.
- `OperationRegistry.kt` owns closed immutable registry construction and lookup.

## Dependency boundary

- Production may depend only on `:kernel`; expose that dependency with `api(project(":kernel"))`.
- Do not import IntelliJ, Gradle, JDBC, filesystem, process, transport, JSON-RPC, serialization,
  legacy `analysis-api`, server, backend, adapter, or service-locator types.
- Definitions are data only. Handlers, functions, runtime capabilities, and implementation
  references belong to later binding and composition modules.

## Contract invariants

- Every operation declares all identity, type, capability, classification, budget, and completeness
  fields without defaults.
- Successful outcomes retain an evidence envelope whose operation ID exactly matches the
  definition. A mismatch is finite typed failure.
- Registry construction rejects every duplicate permanent ID. Lookup returns `Found` or
  `Missing`, never null.
- A stronger prerequisite is only blocker data; the registry never executes it.
- Request, payload, qualification, and rejection values use marker contracts rather than `Any`
  maps or primitive protocols.

## Verification ladder

1. Run `./gradlew :protocol:registry:test --tests io.github.amichne.kast.protocol.registry.OperationRegistryContractTest`.
2. Run `./gradlew :kernel:test :protocol:registry:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
4. Run direct consumers after changing the public registry contract.
