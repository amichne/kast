Target method:
- `ConditionalValue.ContextualResolver.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72-80`

Depth 1: direct callers
- `ConditionalValue.resolve(...)` — `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:35-43`
  - classification: **production**

Depth 2: callers of that direct caller
- `FlagDefinition.evaluateCandidate(...)` calls `candidate.resolve(...)` at `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:165`
  - enclosing function: `FlagDefinition.evaluateCandidate(...)` at `FlagDefinition.kt:144-180`
  - classification: **production**

Within depth 2, I found **no test-file callers**.

Broader upward context (outside the requested depth-2 set): `evaluateCandidate(...)` is reached from `FlagDefinition.evaluateTrace(...)`, which is then used by `FlagDefinition.evaluate(...)` and `Feature<T, C, M>.createRuleDiagnostics(...)`.
