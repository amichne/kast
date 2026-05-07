# vp-cross-module-flow transcript

## Catalog prompt

Trace how FlagValue flows from its definition in konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt to its consumers in other modules. Show the cross-module dependency chain.

## Answer

I anchored the trace at `io.amichne.konditional.internal.serialization.models.FlagValue`, defined in module `konditional-json` at `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:40`.

Module mapping used here is the leading directory segment of each result path:

- `konditional-json`: `FlagValue`, `FlagValueAdapter`, `FlagValueAdapterFactory`, `SerializableFlag`, `SerializableRule`, `SerializableSnapshot`, `ConfigurationCodec`, and `NamespaceExtensions`.
- `konditional-engine`: domain/runtime consumers including `Configuration`, `CompiledNamespaceSchema`, and `ValueType`.
- `konditional-types`: shared public/internal type contracts including `Konstrained` and `KonditionalInternalApi`.

### Encoding flow: domain configuration to JSON FlagValue

`FlagValue` is the serialization model in `konditional-json`. The incoming caller trace for `FlagValue.Companion.from(value: Any): FlagValue<*>` shows the domain-to-serialization chain:

1. `konditional-json` — `FlagValue.Companion.from` converts raw/default/rule values into a `FlagValue<*>`.
2. `konditional-json` — `SerializableFlag.Companion.from(flagDefinition, flagKey)` calls `FlagValue.from(defaultValue)` for a flag default value.
3. `konditional-json` — `SerializableRule.Companion.fromSpec(rule)` calls `FlagValue.from(value)` for rule payload values, and is itself called by `SerializableFlag.Companion.from` when serializing rules.
4. `konditional-json` — `SerializableSnapshot.Companion.from(configuration)` calls `SerializableFlag.from(flag, feature.id)`.
5. `konditional-json` — `ConfigurationCodec.encode(configuration)` calls `SerializableSnapshot.from(configuration)` and serializes the result with Moshi.
6. `konditional-json` — `ConfigurationCodec.encode(namespace)` delegates to `encode(namespace.configuration)`.
7. `konditional-json` — public `Namespace.toJson()` delegates to `ConfigurationCodec.encode(this)`.

The cross-module input to this chain is `konditional-engine`: `SerializableSnapshot.Companion.from(configuration: Configuration)` takes `Configuration` from `konditional-engine/src/main/kotlin/io/amichne/konditional/core/instance/Configuration.kt`, so engine configuration data is consumed by the JSON module and materialized as `FlagValue` inside the serializable snapshot.

### Adapter flow: FlagValue through Moshi

The incoming trace for `FlagValue` shows `konditional-json` `FlagValueAdapter : JsonAdapter<FlagValue<*>>()` as the direct adapter consumer. The incoming trace for `FlagValueAdapterFactory` shows:

`FlagValueAdapterFactory` → `ConfigurationCodec.defaultMoshi()` → private `moshi` → private `snapshotAdapter` → `ConfigurationCodec.encode(...)` and `ConfigurationCodec.decode(...)`.

So every snapshot encode/decode route installs the `FlagValueAdapterFactory`, and Moshi routes `FlagValue` subclasses through `FlagValueAdapter`.

### Decoding flow: JSON FlagValue back to engine consumers

The decode-side incoming/outgoing caller traces show the reverse path:

1. `konditional-json` — public `Namespace.fromJson(json)` calls `ConfigurationCodec.decode(json, this)`.
2. `konditional-json` — `ConfigurationCodec.decode` parses `SerializableSnapshot` and calls `snapshot?.toConfiguration(CompiledNamespaceSchema.from(namespace))`.
3. `konditional-json` — `SerializableSnapshot.toConfiguration(schema)` calls `materializedFlags(schema)` and constructs a `Configuration`.
4. `konditional-json` — `materializedFlags` calls `SerializableFlag.toFlagPair(schema)`.
5. `konditional-json` — `SerializableFlag.toFlagPair` calls private `SerializableFlag.toFlagDefinition(...)`.
6. `konditional-engine` — `SerializableSnapshot.toConfiguration` consumes `CompiledNamespaceSchema` and returns/constructs `Configuration` from `konditional-engine`.

That makes the cross-module dependency chain:

`konditional-engine.Configuration` / `konditional-engine.CompiledNamespaceSchema`
→ `konditional-json.SerializableSnapshot`
→ `konditional-json.SerializableFlag` / `SerializableRule`
→ `konditional-json.FlagValue`
→ `konditional-json.FlagValueAdapterFactory` / `FlagValueAdapter`
→ JSON snapshot boundary
→ `konditional-json.SerializableSnapshot.toConfiguration`
→ `konditional-engine.Configuration`.

Additional outgoing traces from `FlagValue` show `konditional-engine.ValueType` as the value-type classifier dependency, and `konditional-types.Konstrained` plus `konditional-types.KonditionalInternalApi` as shared type-contract dependencies referenced from the `FlagValue` model. No code was mutated.

## Kast evidence used

- `kast_resolve` for `FlagValue`, `SerializableSnapshot`, `toConfiguration`, and `toFlagDefinition`.
- `kast_callers` incoming and outgoing for `FlagValue`, `FlagValueAdapterFactory`, `ConfigurationCodec.encode`, `ConfigurationCodec.decode`, `SerializableSnapshot.Companion.from`, `FlagValue.Companion.from`, `SerializableSnapshot.toConfiguration`, `SerializableFlag.toFlagDefinition`, `Namespace.toJson`, and `Namespace.fromJson`.
- Every module attribution above is by the leading directory segment of the returned absolute file paths.
