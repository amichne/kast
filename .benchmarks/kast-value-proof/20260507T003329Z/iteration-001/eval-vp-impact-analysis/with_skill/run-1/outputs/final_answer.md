I resolved the exact target to `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve` (`konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:72`), excluding the sibling `StaticResolver.resolve` and `Resolver.resolve` overloads.

Direct callers (depth 1): none found.
Second hop (callers of callers, depth 2): none, because there are no direct callers.

Test vs production split:

- test callers: 0
- production callers: 0

Kast reported the exact-symbol search as exhaustive over `583/583` candidate files.
