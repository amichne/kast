# Transcript

## Prompt
Find all usages of the key property on Feature, not every key in the repo. List each call site with file path and line number.

## Target workspace used
`/Users/amichne/code/konditional`

## Major tool calls / steps
- Used `kast_resolve` to disambiguate `Feature.key` to `io.amichne.konditional.core.features.Feature.key`.
- Used `kast_references` on that exact property with the declaring file as `fileHint`.
- Read the returned `searchScope` metadata to confirm whether the search was exhaustive.

## Key findings
- There are 8 exact usages of `Feature.key`.
- The search was exhaustive over 583 candidate files; it was not sampled or truncated.

## Final answer
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
