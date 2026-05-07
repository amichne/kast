`FlagValue` is defined at `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:40`.

Flow from definition outward:

1. **Definition (`konditional-json`)**
   - `FlagValue` models typed serialized flag payloads and exposes `from(value)` plus `extractValue(...)` / validation helpers.

2. **Immediate in-module consumers (`konditional-json`)**
   - `SerializableRule` stores `val value: FlagValue<*>` and converts engine `SerializedFlagRuleSpec` values through `FlagValue.from(...)` (`SerializableRule.kt:21-67`).
   - `SerializableFlag` stores `val defaultValue: FlagValue<*>`, uses `FlagValue.from(...)` on export, and `extractValue(...)` on import to rebuild engine `FlagDefinition` / `Feature` pairs (`SerializableFlag.kt:35-169`).
   - `SerializableSnapshot` aggregates `SerializableFlag` values and materializes an engine `Configuration` (`SerializableSnapshot.kt:18-63`).
   - `ConfigurationCodec` encodes/decodes `SerializableSnapshot` (`ConfigurationCodec.kt:28-72`).

3. **Public handoff out of the json module**
   - `NamespaceExtensions.kt:9-14` exposes `Namespace.toJson()` / `Namespace.fromJson(...)`, which call `ConfigurationCodec`.
   - At that boundary, `FlagValue` has already been converted back into engine types (`Feature`, `FlagDefinition`, `Configuration`).

4. **Consumers in other modules**
   - I found **no direct `FlagValue` references** in `konditional-engine`, `konditional-types`, or `smoke-test`.
   - The downstream consumer sees the reconstructed engine configuration instead. `smoke-test/src/test/kotlin/SmokeTest.kt:33-37` exercises that public path through `toJson()` / `fromJson()`.

Cross-module dependency chain:
- `konditional-types` -> `konditional-engine` -> `konditional-json` -> `smoke-test`

So the important result is: `FlagValue` does **not** cross module boundaries as a direct type; it is an internal json-module representation that gets translated back into engine-domain types before other modules consume the data.
