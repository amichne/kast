# vp-impact-analysis transcript

## Catalog prompt

```text
I want to delete ConditionalValue.ContextualResolver.resolve(). Show me every direct caller, then for each caller show its callers (depth 2). Identify which of those callers are in test files vs production code.
```

## Method

Workspace root: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with`.

Used native Kast semantic operations with that `workspaceRoot`:

- `kast_resolve(symbol="resolve", kind="function", containingType="ConditionalValue.ContextualResolver")`
- `kast_callers(symbol="resolve", containingType="ConditionalValue.ContextualResolver", direction="incoming", depth=2)`
- `kast_callers(symbol="evaluateCandidate", kind="function", containingType="io.amichne.konditional.core.FlagDefinition", direction="incoming", depth=2)`
- `kast_metrics(metric="impact", symbol="io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve", depth=2)`
- `kast_references(...)` was attempted for blast radius, but the native wrapper returned an internal request-shape error (`unknown key includeUsageSiteScope`). I therefore used the successful call hierarchy plus impact metric evidence for the caller list.

## Anchor / disambiguation evidence

Kast reported the target alternatives for `resolve` in `ConditionalValue.kt`:

- `io.amichne.konditional.rules.ConditionalValue.Resolver.resolve`
- `io.amichne.konditional.rules.ConditionalValue.StaticResolver.resolve`
- `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve`

The selected call hierarchy resolved to the `ConditionalValue.resolve` dispatch method at:

- `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:35`
- preview: `internal fun resolve(`

For the requested concrete nested resolver, Kast also resolved the class:

- `io.amichne.konditional.rules.ConditionalValue.ContextualResolver`
- `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:69`
- preview: `private class ContextualResolver<T : Any, C : Context>(`
- supertype: `io.amichne.konditional.rules.ConditionalValue.Resolver`

## Direct callers of `ConditionalValue.ContextualResolver.resolve()`

Kast call hierarchy finds one direct semantic call path through `ConditionalValue.resolve` / resolver dispatch:

1. `io.amichne.konditional.core.FlagDefinition.evaluateCandidate`
   - classification: **production code**
   - declaration: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:144`
   - call site: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:165`
   - preview: `value = candidate.resolve(`

No direct caller returned by Kast is in a test file.

## Callers of each direct caller, depth 2

For direct caller `FlagDefinition.evaluateCandidate`:

Depth 1:

1. local property `matchedTrace`
   - classification: **production code**
   - location: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:125`
   - call site: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:127`
   - preview: `evaluateCandidate(`

Depth 2:

1. `io.amichne.konditional.core.FlagDefinition.evaluateTrace`
   - classification: **production code**
   - declaration: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:102`
   - call site: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:135`
   - preview: `matchedTrace`

No depth-1 or depth-2 caller returned by Kast is in a test file.

## Test vs production summary

Production callers:

- `FlagDefinition.evaluateCandidate` (`src/main`, direct caller)
- local `matchedTrace` (`src/main`, caller of `evaluateCandidate`)
- `FlagDefinition.evaluateTrace` (`src/main`, depth-2 caller of `evaluateCandidate`)

Test callers:

- None returned by Kast.

## Impact metric

`kast_metrics(metric="impact", symbol="io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve", depth=2)` returned `METRICS_SUCCESS` with an empty `results` array, so the impact index did not add extra callers beyond the call hierarchy above.

## Notes

The native `kast_references` operation failed before returning results because the wrapper sent `includeUsageSiteScope` to a backend that rejected that field. This failure was preserved here rather than replacing semantic reference analysis with text search.
