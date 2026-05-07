These are the `Feature.key` usages I found (and only these — excluding unrelated `key` identifiers elsewhere in the repo):

Explicit property reads:
- `konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:61` — `key.key`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:158` — `feature.key`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/internal/builders/FlagBuilder.kt:158` — `feature.key`

Implicit `Feature.key` reads inside `Feature<T, C, M>` extension functions:
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:100`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:111`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:141`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:150`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:164`
