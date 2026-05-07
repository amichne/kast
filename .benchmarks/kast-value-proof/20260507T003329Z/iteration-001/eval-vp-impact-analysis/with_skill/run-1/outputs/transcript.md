# Transcript

## Prompt
I want to delete ConditionalValue.ContextualResolver.resolve(). Show me every direct caller, then for each caller show its callers (depth 2). Identify which of those callers are in test files vs production code.

## Target workspace used
`/Users/amichne/code/konditional`

## Major tool calls / steps
- Used `kast_resolve` to pin the exact `ContextualResolver.resolve` override.
- Used `kast_callers` with `direction=incoming`, `depth=2` to build the caller hierarchy.
- Cross-checked with `kast_references` on the same exact symbol to confirm there are no direct references.

## Key findings
- There are no direct callers of the exact `ContextualResolver.resolve` override.
- Accordingly, there is no depth-2 caller chain and no test/production caller split to report.

## Final answer
I resolved the exact target to `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve` (`konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72`), excluding the sibling `StaticResolver.resolve` and `Resolver.resolve` overloads.

Direct callers (depth 1): none found.
Second hop (callers of callers, depth 2): none, because there are no direct callers.

Test vs production split:

- test callers: 0
- production callers: 0

Kast reported the exact-symbol search as exhaustive over `583/583` candidate files.
