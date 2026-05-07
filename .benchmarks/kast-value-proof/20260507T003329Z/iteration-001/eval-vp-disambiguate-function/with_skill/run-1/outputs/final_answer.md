Resolved the exact symbol to `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve` at `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72`, excluding the two other `resolve` candidates (`StaticResolver.resolve` and `Resolver.resolve`).

Incoming call hierarchy for that exact override:

- `ConditionalValue.ContextualResolver.resolve` (`ConditionalValue.kt:72`)
  - no incoming callers found

Kast also reported the reference search as exhaustive over `583/583` candidate files, with zero direct references to this override.
