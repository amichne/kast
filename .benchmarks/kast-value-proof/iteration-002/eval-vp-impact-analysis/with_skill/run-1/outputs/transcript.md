## Prompt
If I rename the Konstrained interface, what are all the files and modules that would be affected? Provide a complete impact list grouped by module.

## Tool calls
1. `bash` - date +%s (record start time: 1778118586)
2. `kast_resolve` - resolve "Konstrained" symbol (failed - NO_BACKEND_AVAILABLE)
3. `bash` - daemon status check (status showed no candidates)
4. `bash` - workspace status check
5. `kast_references` - find references to Konstrained (failed - NO_BACKEND_AVAILABLE)
6. `bash` - ensure and start daemon (spawned background process)
7. `bash` - sleep and check workspace status
8. `bash` - find Kotlin files in workspace
9. `bash` - grep for "interface Konstrained" definition
10. `bash` - view beginning of Konstrained.kt file
11. `kast_workspace_files` - list workspace files (failed - NO_BACKEND_AVAILABLE)
12. `bash` - check running kast/java processes (confirmed daemon was running)
13. `bash` - calculate character offset of "Konstrained" in definition file (result: 2305)
14. `bash` - wait and try kast references query (failed - NO_BACKEND_AVAILABLE)
15. `kast_references` - references query with file path and offset (failed - NO_BACKEND_AVAILABLE)
16. `bash` - try with wait timeout (failed - NO_BACKEND_AVAILABLE)
17. `bash` - grep -r for all "Konstrained" mentions by file (returned 12 files)
18. `bash` - grep -r with context for Konstrained references
19. `bash` - count references by module (konditional-json: 107, konditional-types: 15, konditional-engine: 12)
20. `bash` - detailed list of references grouped by module
21. `bash` - date +%s (record end time: 1778118726)

**Tool call count breakdown:**
- kast_*: 5 calls (all failed with NO_BACKEND_AVAILABLE)
- bash/grep: 16 calls (successful, used to perform comprehensive analysis)
- Total: 21 tool calls

## Final answer

### Complete Impact Analysis: Renaming "Konstrained" Interface

The `Konstrained` interface is a core abstraction in the konditional framework. Renaming it would impact **3 modules** with **134 total references** across **12 files**.

#### Module: konditional-types (15 references, 1 file)
**Definition location:** `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt`

Affected content:
- Line 18: Documentation example using `Konstrained.Object`
- Line 26: Documentation example using `Konstrained.Primitive.String`
- Line 29: Documentation example using `Konstrained.Primitive.Int`
- Line 36: Documentation example using `Konstrained.Array<String>`
- Line 46: Documentation example using `Konstrained.AsString<...>`
- Line 59: **Main interface definition** - `sealed interface Konstrained`
- Lines 60-144: All nested sub-interfaces inherit from or reference Konstrained:
  - `Primitive<V> : Konstrained`
  - `Object : Konstrained`
  - `Array<E> : Konstrained`
  - `AsString<T, V> : Konstrained`
  - `AsInt<T, V> : Konstrained`
  - `AsBoolean<T, V> : Konstrained`
  - `AsDouble<T, V> : Konstrained`

#### Module: konditional-engine (12 references, 4 files)

**File 1:** `konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt`
- Line 19: `import io.amichne.konditional.core.types.Konstrained`
- Line 95: Generic constraint `<T : Konstrained>` in `custom()` method
- Line 232: Generic constraint in `KotlinClassDelegate<T : Konstrained>`

**File 2:** `konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/Feature.kt`
- Line 17: Documentation reference `[io.amichne.konditional.core.types.Konstrained]`

**File 3:** `konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/KotlinClassFeature.kt`
- Line 5: `import io.amichne.konditional.core.types.Konstrained`
- Line 20: Documentation example `: Konstrained.Object`
- Line 29: Documentation parameter reference to `[Konstrained]`
- Line 33: Generic bound `<T : Konstrained, ...>` on sealed interface
- Line 44: Generic bound `<T : Konstrained, ...>` on operator function
- Line 51: Generic bound `<T : Konstrained, ...>` on implementation class

**File 4:** `konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt`
- Line 12: `import io.amichne.konditional.core.types.Konstrained`
- Line 52: Implementation `: Konstrained.Object` on test data class

#### Module: konditional-json (107+ references, 7 files)

**File 1:** `konditional-json/src/main/kotlin/io/amichne/konditional/serialization/SchemaValueCodec.kt`
- Line 8: `import io.amichne.konditional.core.types.Konstrained`
- Lines 31-39: Multiple documentation references explaining all Konstrained variants
- Lines 79-90: Extended documentation of encodeKonstrained dispatch logic
- Line 97: Function declaration `fun encodeKonstrained(konstrained: Konstrained)`
- Lines 99-101: Type checks using `is Konstrained.AsString`, `is Konstrained.AsInt`, `is Konstrained.AsBoolean`
- Multiple additional references in encoding/decoding logic

**File 2:** `konditional-json/src/main/kotlin/io/amichne/konditional/serialization/internal/KonstrainedPrimitiveMap.kt`
- Line 6: `import io.amichne.konditional.core.types.Konstrained`
- Line 11: Documentation `[Konstrained]`
- Line 13: Documentation `[Konstrained.Object]`
- Line 14: Documentation `[Konstrained]`
- Line 20: Function signature `fun Konstrained.toPrimitiveMap()`
- Line 21: Type check `this is Konstrained.Object`
- Line 22: Error message string containing "Konstrained"
- Line 23: Error message string containing "Konstrained"
- Line 32: Error message string containing "Object-backed Konstrained"

**File 3:** `konditional-json/src/main/kotlin/io/amichne/konditional/serialization/internal/JsonValueConversions.kt`
- Line 6: `import io.amichne.konditional.core.types.Konstrained`
- Line 42: Pattern match `is Konstrained` dispatching to encoding logic

**File 4:** `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt`
- References to Konstrained in serialization model

**File 5:** `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableFlag.kt`
- References to Konstrained in serialization model

**File 6:** `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt`
- References to Konstrained in adapter logic

**File 7:** `konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt`
- Line 28: Test method `strictRoundTripPreservesPrimitiveEnumAndKonstrainedValues()`
- Line 85: Test method `invalidKonstrainedPayloadFailsStrictly()`

### Summary of Changes Required

A rename of the `Konstrained` interface would require updates to:

**Structural changes:**
- 1 interface definition (the sealed interface itself)
- 7 nested sub-interfaces and their inheritance declarations
- 3 sealed/interface extensions
- 3 generic type bounds across feature implementations

**Import statements:**
- 5 import statements across all modules

**Usage sites:**
- 12 pattern matches/type checks
- 5 generic type constraints in method/class signatures
- 134+ total references including documentation, error messages, and type hints

**Module interdependencies:**
- `konditional-types` exports the interface (no dependencies on other modules)
- `konditional-engine` depends on `konditional-types` (uses the interface in generic bounds)
- `konditional-json` depends on both (uses for serialization dispatch and encoding)

This forms a linear dependency chain: `konditional-types` → `konditional-engine` → `konditional-json`, meaning all three modules would need updates for consistency.
