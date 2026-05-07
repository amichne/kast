## Prompt
Find all usages of the key property on Feature, not every key in the repo. List each call site with file path and line number.

## Tool calls
1. `kast_resolve` - Resolved Feature class to identify the target interface
2. `kast_resolve` - Resolved Feature.key property to get the symbol location (offset 1350 in Feature.kt)
3. `kast_references` - Attempted to find all references to Feature.key (failed with schema error)
4. `bash grep` - Searched for ".key" usages in all Kotlin files (7 total matches found)
5. `bash sed` - Examined context around each match to determine which were Feature.key usages
6. Additional searches to verify variable types and confirm Feature references

## Final answer

Found 3 usages of the key property on Feature:

1. **File**: `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt  
   **Line**: 61  
   **Code**: `key.key` (where key parameter is of type Feature<T, C, M>)

2. **File**: `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt  
   **Line**: 158  
   **Code**: `feature.key` (where feature is a property of type Feature<T, C, M>)

3. **File**: `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/builders/FlagBuilder.kt  
   **Line**: 158  
   **Code**: `feature.key` (where feature is a property of type Feature<T, C, M>)

All three usages correctly access the String key property on Feature instances. These were verified by examining the type declarations and confirming that the variables (key, feature) are indeed Feature types.
