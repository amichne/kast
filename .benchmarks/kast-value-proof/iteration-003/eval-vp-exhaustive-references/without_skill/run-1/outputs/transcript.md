# Benchmark transcript: exhaustive FlagValue references without Kast skill

Workspace: /Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without
Prompt: Find every reference to FlagValue across the entire workspace. Tell me whether the search was exhaustive — did it cover every candidate file, or was it sampled/truncated?

Approach: ran recursive grep across the workspace using `grep -RIn -- "FlagValue" "$WORKSPACE"`. No Kast tools, kast CLI, or IDE/LSP semantic tools were used.

## Command

```sh
grep -RIn -- "FlagValue" /Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without
```

## Hits

```text
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/serialization/snapshot/ConfigurationCodec.kt:14:import io.amichne.konditional.internal.serialization.adapters.FlagValueAdapterFactory
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/serialization/snapshot/ConfigurationCodec.kt:59:            .add(FlagValueAdapterFactory)
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableFlag.kt:31: * Now uses type-safe FlagValue instead create type-erased Any values.
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableFlag.kt:37:    val defaultValue: FlagValue<*>,
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableFlag.kt:77:                defaultValue = FlagValue.from(defaultValue),
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:40:internal sealed class FlagValue<out T : Any> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:44:     * Returns the ValueType corresponding to this FlagValue subclass.
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:53:    ) : FlagValue<Boolean>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:60:    ) : FlagValue<String>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:67:    ) : FlagValue<Int>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:74:    ) : FlagValue<Double>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:87:    ) : FlagValue<String>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:104:    ) : FlagValue<Map<String, Any?>>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:121:    ) : FlagValue<Any>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:169:         * Creates a [FlagValue] from an untyped value by inferring its type.
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:175:        fun from(value: Any): FlagValue<*> =
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:193:        private fun fromKonstrained(value: Konstrained): FlagValue<*> =
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:212: * for storage in [FlagValue.KonstrainedPrimitive.value].
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableRule.kt:16: * Now uses type-safe FlagValue instead create type-erased SerializableValue,
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableRule.kt:22:    val value: FlagValue<*>,
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableRule.kt:55:                value = FlagValue.from(value),
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/AGENTS.md:9:- `FlagValue.kt`
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/AGENTS.md:9:- `FlagValueAdapter.kt`
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/AGENTS.md:10:- `FlagValueJsonMaps.kt`
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:11:import io.amichne.konditional.internal.serialization.models.FlagValue
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:16: * Custom Moshi adapter for the [FlagValue] sealed class.
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:18: * Serializes [FlagValue] subclasses with a `type` discriminator field for type-safe
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:23: * - `"BOOLEAN"` → [FlagValue.BooleanValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:24: * - `"STRING"` → [FlagValue.StringValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:25: * - `"INT"` → [FlagValue.IntValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:26: * - `"DOUBLE"` → [FlagValue.DoubleValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:27: * - `"ENUM"` → [FlagValue.EnumValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:28: * - `"DATA_CLASS"` → [FlagValue.DataClassValue]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:29: * - `"KONSTRAINED_PRIMITIVE"` → [FlagValue.KonstrainedPrimitive]
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:32:internal class FlagValueAdapter : JsonAdapter<FlagValue<*>>() {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:35:        value: FlagValue<*>?,
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:44:            is FlagValue.BooleanValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:48:            is FlagValue.StringValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:52:            is FlagValue.IntValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:56:            is FlagValue.DoubleValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:60:            is FlagValue.EnumValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:65:            is FlagValue.DataClassValue -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:71:            is FlagValue.KonstrainedPrimitive -> {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:81:    override fun fromJson(reader: JsonReader): FlagValue<*> =
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:82:        readFlagValueParts(reader).let { parsed ->
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:85:                "BOOLEAN" -> FlagValue.BooleanValue(requireBoolean(parsed.value, type = type))
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:86:                "STRING" -> FlagValue.StringValue(requireString(parsed.value, type = type))
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:87:                "INT" -> FlagValue.IntValue(requireInt(parsed.value, type = type))
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:88:                "DOUBLE" -> FlagValue.DoubleValue(requireDouble(parsed.value, type = type))
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:90:                    FlagValue.EnumValue(
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:95:                    FlagValue.DataClassValue(
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:100:                    FlagValue.KonstrainedPrimitive(
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:110:                else -> invalid("Unknown FlagValue type: $type")
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:116:internal object FlagValueAdapterFactory : JsonAdapter.Factory {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:117:    private val flagValueAdapter = FlagValueAdapter()
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:123:    ): JsonAdapter<*>? = flagValueAdapter.takeIf { getRawType(type) == FlagValue::class.java }
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:132:private data class FlagValueParts(
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:140:private fun readFlagValueParts(reader: JsonReader): FlagValueParts {
/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt:160:    return FlagValueParts(
```

## Exhaustiveness statement

The search was exhaustive for grep-readable files under the workspace path: `grep -RIn` recursively traversed the entire workspace and the captured output was written directly to this transcript. The result was not sampled or truncated by the command pipeline.

grep_exit_status: 0
