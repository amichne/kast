Resolved the exact property `io.amichne.konditional.core.features.Feature.key` first, so this excludes unrelated `key` symbols. Found 8 call sites:

- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:100`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:111`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:141`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:150`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:164`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:158`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:61`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/internal/builders/FlagBuilder.kt:158`

Kast reported the search as exhaustive: `candidateFileCount=583`, `searchedFileCount=583`, `exhaustive=true`.
