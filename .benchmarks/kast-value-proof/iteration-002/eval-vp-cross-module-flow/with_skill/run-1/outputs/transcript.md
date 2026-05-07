## Prompt

Trace how Feature flows from its definition in konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/Feature.kt to its consumers in other modules. Show the cross-module dependency chain.

## Tool calls

1. **bash**: Record start time
2. **bash**: List Kotlin files in workspace
3. **bash**: Find Feature interface implementations using grep
4. **bash**: Count total Feature references
5. **bash**: Find all gradle build files (module discovery)
6. **bash**: Create comprehensive trace of Feature dependencies
7. **bash**: Get workspace and module summary
8. **bash**: Get cross-module consumer breakdown
9. **bash**: Create detailed dependency chain
10. **bash**: Create module dependency graph
11. **bash**: Show usage patterns in key files
12. **bash**: Show cross-module usage in serialization
13. **bash**: Record end time

## Final answer

### Feature Cross-Module Dependency Chain

**Feature Definition**
- File: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/Feature.kt`
- Type: `sealed interface Feature<T : Any, C : Context, out M : Namespace> : Identifiable.ById`
- Module: `konditional-engine`
- Supertype: `Identifiable.ById` (from `konditional-types`)

### Dependency Chain Structure

```
konditional-types (base)
└── Identifiable.ById
    └── Feature (in konditional-engine)
        ├─ BooleanFeature
        ├─ StringFeature
        ├─ IntFeature
        ├─ DoubleFeature
        ├─ EnumFeature
        └─ KotlinClassFeature
```

### Cross-Module Dependencies (77 references in konditional-engine)

**Layer 1: Core API Consumers (konditional-engine/core)**
- `Namespace.kt`: Stores and manages feature registration; tracks features in `mutableListOf<Feature<*, *, *>>()`
- `FlagDefinition.kt`: Wraps Feature with metadata; contains `val feature: Feature<T, C, M>`
- `Configuration.kt`: Accesses features at runtime for evaluation
- `NamespaceRegistry.kt`: Registry lookup mechanism for features

**Layer 2: DSL Rule System (konditional-engine/core/dsl)**
- `RuleSet.kt`: Stores feature rules; has `val feature: Feature<T, C, M>`
- `RuleScope.kt`: DSL operators that yield conditions with features; `infix fun yields(feature: Feature<T, C, M2>)`
- `RuleValueScope.kt`: Rule value processing with feature context

**Layer 3: Schema Compilation (konditional-engine/core/schema)**
- `CompiledNamespaceSchema.kt`: Compiles namespace metadata; traverses features for schema generation
- Contains: `Feature<*, *, *>` entries for namespace schema compilation

**Layer 4: SPI Extension Points (konditional-engine/core/spi)**
- `FeatureRegistrationHook.kt`: SPI for lifecycle hooks; `fun onFeatureDefined(feature: Feature<*, *, *>)`
- `FeatureRegistrationHooks.kt`: Notifies observers when features are defined

**Layer 5: API Layer (konditional-engine/api)**
- `FeatureEvaluation.kt`: Public API for feature evaluation

**Layer 6: Internal Builders (konditional-engine/internal)**
- `FlagBuilder.kt`: Constructs flags; stores `private val feature: Feature<T, C, M>`
- `FlagDefinitionInternal.kt`: Internal flag metadata builder

### Cross-Module Consumers (8 references total)

**konditional-json module (7 references)**
- Depends on: `konditional-engine`, `konditional-types`
- `SerializableFlag.kt`:
  - Deserializes Feature references from JSON snapshots
  - Methods: `resolveFeature(schema): Result<Feature<*, *, *>>`, `toFlagDefinition(conditional: Feature<T, C, M>)`
  - Accesses feature properties: `namespace.flag(this).defaultValue`
  
- `SerializableSnapshot.kt`:
  - Manages feature snapshots: `LinkedHashMap<Feature<*, *, *>, FlagDefinition<*, *, *>>`
  - Reconstructs Feature objects during deserialization

**konditional-types module (1 reference)**
- `Identifiable.kt`: Defines the `Identifiable.ById` supertype that Feature implements
- Establishes the identity contract for features

### Flow Summary

1. **Definition**: Feature sealed interface defined in konditional-engine/core/features
2. **Type System**: Specializations (BooleanFeature, StringFeature, etc.) in same module
3. **Core Registration**: Namespace accumulates Feature instances in maps for lifecycle management
4. **API Surface**: FlagDefinition wraps Feature with metadata for public consumption
5. **DSL Integration**: RuleSet and RuleScope incorporate features in conditional logic
6. **Schema Compilation**: CompiledNamespaceSchema traverses feature metadata for type information
7. **SPI Hooks**: FeatureRegistrationHook enables extensibility on feature lifecycle
8. **Cross-Module Bridge**: konditional-json serializes/deserializes Feature references from configurations
9. **Identity Chain**: Identifiable.ById (konditional-types) → Feature (konditional-engine) → Subtypes

### Module Import Dependency Graph

```
konditional-types
  (provides: Identifiable.ById)
        ↑
        │ depends on
        │
konditional-engine (provides: Feature + 6 subtypes)
        ↑
        │ depends on
        │
konditional-json (consumes: Feature for serialization)
```

**Reference Counts**: 
- konditional-engine: 77 references (internal usage)
- konditional-json: 7 references (cross-module serialization)
- konditional-types: 1 reference (supertype definition)
