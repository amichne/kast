# Transcript

## Prompt
Find callers of ConditionalValue.ContextualResolver.resolve(), not every function named resolve in the project. Show the call hierarchy.

## Target workspace used
`/Users/amichne/code/konditional`

## Major tool calls / steps
- Used `kast_resolve` with `containingType=io.amichne.konditional.rules.ConditionalValue.ContextualResolver` to pin the exact override.
- Used `kast_callers` with `direction=incoming`, `depth=2` on that exact function.
- Cross-checked with `kast_references` to confirm there are no direct references to that override.

## Key findings
- The exact target is the ContextualResolver override at `ConditionalValue.kt:72`.
- No incoming callers were found; the hierarchy is root-only.
- The exact-symbol search was exhaustive.

## Final answer
Resolved the exact symbol to `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve` at `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72`, excluding the two other `resolve` candidates (`StaticResolver.resolve` and `Resolver.resolve`).

Incoming call hierarchy for that exact override:

- `ConditionalValue.ContextualResolver.resolve` (`ConditionalValue.kt:72`)
  - no incoming callers found

Kast also reported the reference search as exhaustive over `583/583` candidate files, with zero direct references to this override.
