# Transcript

## Prompt
Find callers of ConditionalValue.ContextualResolver.resolve(), not every function named resolve in the project. Show the call hierarchy.

## Target workspace used
/Users/amichne/code/konditional

## Major tool calls / steps
- Read `ConditionalValue.kt` to locate the nested `ContextualResolver.resolve` implementation.
- Used targeted `rg` searches for `candidate.resolve(` and `evaluateTrace(` to follow the specific call path upward.
- Read `FlagDefinition.kt` and `FeatureEvaluation.kt` slices to confirm the exact callers and line numbers.

## Key findings
- `ContextualResolver.resolve` is private and only invoked through `ConditionalValue.resolve`.
- That wrapper is only called from `FlagDefinition.evaluateCandidate`.
- The next callers upward are `FlagDefinition.evaluateTrace`, then `FlagDefinition.evaluate` and `Feature.createRuleDiagnostics`.

## Final answer
Call hierarchy for the specific `ConditionalValue.ContextualResolver.resolve()` is:

- `ConditionalValue.ContextualResolver.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72-80`
  - called by `ConditionalValue.resolve(...)` via `resolver.resolve(...)` — `ConditionalValue.kt:35-43`
    - called by `FlagDefinition.evaluateCandidate(...)` at `candidate.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144-180` (call at line 165)
      - called by `FlagDefinition.evaluateTrace(...)` — `FlagDefinition.kt:102-141`
        - called by `FlagDefinition.evaluate(...)` — `FlagDefinition.kt:74-79`
        - called by `Feature<T, C, M>.createRuleDiagnostics(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:128-170` (call at line 134)

This follows the `ConditionalValue.ContextualResolver.resolve` path specifically, not other unrelated `resolve` methods in the repo.
