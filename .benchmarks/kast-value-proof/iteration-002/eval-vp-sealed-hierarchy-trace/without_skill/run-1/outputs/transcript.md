## Prompt
List every implementation of the sealed interface Konstrained. For each implementation, show its file location and which module it belongs to.

## Tool calls

1. **view**: Examined workspace structure at `/tmp/kast-vp-rebench/konditional-without`
2. **bash grep**: Searched for sealed interface Konstrained definition using grep pattern `sealed.*interface.*Konstrained|interface.*Konstrained.*sealed`
3. **bash find + grep**: Listed Kotlin files and searched for implementations
4. **bash grep**: Found direct implementations extending Konstrained using `:\s*Konstrained` patterns
5. **bash grep**: Found direct implementations extending Konstrained using `:\s*Konstrained\s*{` patterns
6. **view**: Examined TestFixtures.kt file containing concrete implementations
7. **bash grep**: Found all classes implementing Konstrained using pattern `class.*:\s*Konstrained|data class.*:\s*Konstrained|object.*:\s*Konstrained`
8. **bash grep**: Searched for comprehensive list of implementations with filtering for actual code lines

## Final answer

The sealed interface `Konstrained` hierarchy includes:

### Sealed Interface Definition
- **File**: `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt`
- **Module**: `konditional-types`
- **Type**: Sealed interface

### Direct Nested Interfaces (Part of Sealed Hierarchy)
All located in: `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt`

1. **Primitive<V>** - sealed interface (extends Konstrained)
   - Module: `konditional-types`
   - Nested interfaces:
     - **Int** - interface (extends Primitive<kotlin.Int>)
     - **String** - interface (extends Primitive<kotlin.String>)
     - **Boolean** - interface (extends Primitive<kotlin.Boolean>)
     - **Double** - interface (extends Primitive<kotlin.Double>)

2. **Object** - interface (extends Konstrained)
   - Module: `konditional-types`

3. **Array<E>** - interface (extends Konstrained)
   - Module: `konditional-types`

4. **AsString<T : Any, V : AsString<T, V>>** - interface (extends Konstrained)
   - Module: `konditional-types`

5. **AsInt<T : Any, V : AsInt<T, V>>** - interface (extends Konstrained)
   - Module: `konditional-types`

6. **AsBoolean<T : Any, V : AsBoolean<T, V>>** - interface (extends Konstrained)
   - Module: `konditional-types`

7. **AsDouble<T : Any, V : AsDouble<T, V>>** - interface (extends Konstrained)
   - Module: `konditional-types`

### Concrete Implementation Classes

1. **RetryPolicy** - data class
   - **File**: `konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt`
   - **Module**: `konditional-engine`
   - **Implements**: `Konstrained.Object`
   - **Location**: Line 47-60

