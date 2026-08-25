# Protocol registry module guide

`:protocol:registry` owns immutable, host-neutral definitions of public operations. It classifies
each permanent operation identity by typed input and semantic outcome, required capability, effect,
cost, scope, finite resource budget, and completeness policy.

## Module map

- `OperationClassification.kt` owns the closed lane, effect, cost, scope, and completeness axes.
- `OperationBlocker.kt` owns common closed reasons that prevent an operation from running.
- `OperationDefinition.kt` binds typed operation metadata to generation-bound kernel outcomes.
- `OperationRegistry.kt` owns closed immutable registry construction and lookup.
- `CanonicalAgentToolDefinitions.kt` owns the two agent-facing read tools, their input models, and
  their exact canonical operation compositions without importing an agent transport.

## Dependency boundary

- Production may depend only on `:kernel` and `:protocol:contract`; expose both dependencies as
  API.
- Do not import IntelliJ, Gradle, JDBC, filesystem, process, transport, JSON-RPC, serialization,
  legacy `analysis-api`, server, backend, adapter, or service-locator types.
- Definitions are data only. Handlers, functions, runtime capabilities, and implementation
  references belong to later binding and composition modules.

## Contract invariants

- Every operation declares all identity, type, capability, classification, budget, and completeness
  fields without defaults.
- Capability identity and its exact Kotlin type are both mandatory; later binding may supply only
  that type and does not gain registry-owned execution authority.
- Successful outcomes retain an evidence envelope whose operation ID exactly matches the
  definition. A mismatch is finite typed failure.
- Registry construction admits exactly one typed definition for each canonical operation and
  rejects missing, duplicate, unknown, untyped, or duplicate-schema metadata. Lookup returns
  `Found` or `Unknown`, never null.
- A stronger prerequisite is only blocker data; the registry never executes it.
- Request, result, qualification, and rejection values use marker contracts rather than `Any`
  maps or primitive protocols.
- `OperationRegistryArtifact` is the typed operation-ID projection consumed by `:protocol:wire`;
  this module does not encode JSON or write generated files.
- Agent tool models name only existing canonical operation definitions. Agent-specific JSON and
  execution stay in outer adapters.

## Verification ladder

1. Run `./gradlew :protocol:registry:test --tests io.github.amichne.kast.protocol.registry.OperationRegistryContractTest`.
2. Run `./gradlew :kernel:test :protocol:registry:test`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
4. Run direct consumers after changing the public registry contract.
