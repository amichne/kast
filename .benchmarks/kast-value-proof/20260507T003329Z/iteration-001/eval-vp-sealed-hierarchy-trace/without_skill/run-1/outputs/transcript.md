# Transcript

## Prompt
Find all usages of the key property on Feature, not every key in the repo. List each call site with file path and line number.

## Target workspace used
/Users/amichne/code/konditional

## Major tool calls / steps
- Read `Feature.kt` to confirm the target property is `Feature.key`.
- Used `rg -n '\.key\b'` to find explicit property reads and a focused `rg -n '\bkey\b' FeatureEvaluation.kt` to catch implicit receiver uses in `Feature` extension functions.
- Read the surrounding file slices to verify each match is a `Feature.key` usage rather than some other `key` identifier.

## Key findings
- There are 8 `Feature.key` call sites in the workspace.
- Three are explicit property reads (`feature.key` / `key.key`), and five are implicit receiver-property reads inside `Feature<T, C, M>` extension functions.
- All of them are in production code under `konditional-engine`.

## Final answer
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
