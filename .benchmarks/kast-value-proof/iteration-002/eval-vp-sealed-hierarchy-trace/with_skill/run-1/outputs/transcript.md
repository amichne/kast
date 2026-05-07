## Prompt
List every implementation of the sealed interface Konstrained. For each implementation, show its file location and which module it belongs to.

## Tool calls

1. **kast_workspace_files** - Listed all modules and their source files in the workspace
2. **kast_resolve** - Attempted to resolve the symbol "Konstrained" (class) to understand its location
3. **bash (grep)** - Searched for sealed interface definitions with pattern `grep -r "sealed.*interface.*Konstrained"`
4. **bash (head)** - Viewed the Konstrained.kt file to understand the sealed interface structure
5. **kast_resolve** - Attempted to resolve fully qualified name "io.amichne.konditional.core.types.Konstrained"
6. **bash (grep)** - Searched for all files containing Konstrained with `grep -r ":\s*Konstrained"`
7. **bash (for loop)** - Extracted Konstrained implementations from each file matching the search
8. **bash (grep)** - Found concrete implementations with patterns like `: Konstrained.Object`
9. **kast_references** - Attempted to find all references to Konstrained interface (failed due to kast version incompatibility)
10. **bash (find + grep)** - Searched for all implementations across entire workspace
11. **bash (grep)** - Searched for each sealed sub-interface: Object, Array, Primitive.String, etc.
12. **bash (grep -n)** - Found RetryPolicy implementation at line 47-52 in TestFixtures.kt
13. **bash (grep -A5)** - Verified KonstrainedPrimitive extends FlagValue, not Konstrained directly
14. **bash (kast callers)** - Attempted to use kast callers to find incoming implementations
15. **bash (find + grep module)** - Listed which modules contain Konstrained-related code
16. **bash (grep -n)** - Found all lines with `: Konstrained.` pattern showing only type parameter usage, not implementations
17. **bash (Python regex)** - Wrote Python script to find concrete implementations using regex
18. **bash (grep)** - Listed all class definitions to identify implementations
19. **bash (Python script)** - Second Python script to find only concrete (non-interface) implementations
20. **bash (grep -rn)** - Listed top-level class/object definitions
21. **bash (grep)** - Final comprehensive search for implementations
22. **bash (date)** - Recorded end time

Total tool calls: 22 (20 bash + 2 kast_resolve + 1 kast_workspace_files + 1 kast_references attempt)

## Final answer

After comprehensive analysis of the workspace at `/tmp/kast-vp-rebench/konditional-with`, I found **1 concrete implementation** of the sealed interface `Konstrained`:

### Implementations

1. **RetryPolicy**
   - **File location**: `konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt`
   - **Module**: `:konditional-engine[testFixtures]`
   - **Line**: 47-52
   - **Implements**: `Konstrained.Object`
   - **Type**: `data class`
   - **Full path**: `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt`

### Notes

- The sealed interface `Konstrained` is defined in `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt`
- The interface has several sealed sub-interfaces:
  - `Konstrained.Object` - for multi-field structures (1 implementation found: RetryPolicy)
  - `Konstrained.Primitive<V>` - for primitive-backed values (no concrete implementations in codebase)
  - `Konstrained.Array<E>` - for array-backed values (no concrete implementations in codebase)
  - `Konstrained.AsString<T, V>` - for string-adapted values (no concrete implementations in codebase)
  - `Konstrained.AsInt<T, V>` - for int-adapted values (no concrete implementations in codebase)
  - `Konstrained.AsBoolean<T, V>` - for boolean-adapted values (no concrete implementations in codebase)
  - `Konstrained.AsDouble<T, V>` - for double-adapted values (no concrete implementations in codebase)

- The documentation in `Konstrained.kt` contains example implementations (Email, RetryCount, Tags) but these are only shown as code examples in KDoc comments, not actual implementations in the codebase.

### Module breakdown

- **konditional-engine[testFixtures]**: 1 implementation (RetryPolicy)
- **All other modules**: 0 implementations
