# Protocol wire module guide

`:protocol:wire` owns the one schema-identified transport envelope and the generated serializer
table for the canonical public operation set.

## Dependency boundary

- Production depends only on `:kernel`, `:protocol:contract`, and `:protocol:registry`.
- Serialization primitives and raw wire text stay inside this module.
- Do not import runtime, service, adapter, platform, filesystem, Gradle, JDBC, or IntelliJ types.
- Wire code transports typed contracts; it does not dispatch operations or gain capabilities.
- The generated `operation-registry.json` projection is encoded here from the registry's typed
  artifact; Gradle owns filesystem output and installed metadata copies those bytes unchanged.
- Every closed request, result, qualification, rejection, and generated metadata schema uses a
  dedicated `@Serializable` wire document and the Kotlin plugin's generated `serializer()`
  factory. Do not assemble or parse known schemas through `JsonObject`, maps, JSON builders, field
  lookups, or hand-written `KSerializer` implementations. `JsonElement` is reserved for the open
  envelope slot before the matching generated document refines it.
- `wireJson` is the sole configured JSON format. Reuse it through `GeneratedWireCodecFactory` and
  declare schema-specific sealed discriminators with `@JsonClassDiscriminator`; do not construct a
  serializer-family `Json` instance.

## Contract invariants

- Serializer-table construction admits exactly one binding for every canonical operation.
- Every request and semantic outcome variant uses the same schema-identified envelope.
- Decoding refines raw schema, operation, generation, and payload values before returning them.
- Unknown schema, unknown operation, malformed envelopes, and invalid payloads are closed failures.

## Verification ladder

1. Run `./gradlew :protocol:wire:test --tests '*OperationWireContractTest'`.
2. Run `./gradlew :protocol:registry:test :protocol:wire:test`.
3. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
