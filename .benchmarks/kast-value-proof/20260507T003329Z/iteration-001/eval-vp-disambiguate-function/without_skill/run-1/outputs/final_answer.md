Call hierarchy for the specific `ConditionalValue.ContextualResolver.resolve()` is:

- `ConditionalValue.ContextualResolver.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72-80`
  - called by `ConditionalValue.resolve(...)` via `resolver.resolve(...)` — `ConditionalValue.kt:35-43`
    - called by `FlagDefinition.evaluateCandidate(...)` at `candidate.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144-180` (call at line 165)
      - called by `FlagDefinition.evaluateTrace(...)` — `FlagDefinition.kt:102-141`
        - called by `FlagDefinition.evaluate(...)` — `FlagDefinition.kt:74-79`
        - called by `Feature<T, C, M>.createRuleDiagnostics(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:128-170` (call at line 134)

This follows the `ConditionalValue.ContextualResolver.resolve` path specifically, not other unrelated `resolve` methods in the repo.
