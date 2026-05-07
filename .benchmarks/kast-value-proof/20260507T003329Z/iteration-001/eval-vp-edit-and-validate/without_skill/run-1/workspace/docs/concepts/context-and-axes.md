# Context and axes

`Context` is the runtime input to evaluation. Konditional keeps it small and
composable so features can depend on only the dimensions they actually need.

## Standard context mix-ins

The base `Context` interface can be extended with mix-ins for common runtime
dimensions.

- `Context.LocaleContext`
- `Context.PlatformContext`
- `Context.VersionContext`
- `Context.StableIdContext`

You opt into these dimensions only when a rule or rollout actually uses them.

## Axes for domain-specific targeting

Axes are how you model custom dimensions such as environment, region, tenant,
or product line. An axis value is an enum that implements `AxisValue<T>`.

```kotlin
enum class Environment : AxisValue<Environment> {
    DEV,
    PROD,
}
```

Contexts expose axis selections through `axes(...)`, and rules target them
with `constrain(...)`.

## Stable identifiers and rollout

`StableIdContext` is the important mix-in for gradual rollout. Ramp-up uses the
stable ID, the feature key, and the salt to compute a deterministic bucket.

If you plan to roll out progressively, add a stable ID early so the context
shape does not have to change later.

## Next steps

Once the context model is clear, the next question is how rules are matched
and ordered when more than one rule could apply.

- [Read about rules and precedence](rules-and-precedence.md)

