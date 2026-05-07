# Add ramp-up

Ramp-up is how Konditional does gradual rollout. The rule still returns a
typed value, but eligibility is now gated by a deterministic bucket derived
from the namespace, the feature, and a stable ID.

## Add a stable ID to the context

Ramp-up is most useful when the context carries a stable identity. Add the
`StableIdContext` mix-in to the context you use for rollout decisions.

```kotlin
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.id.StableId

data class RolloutContext(
    override val stableId: StableId,
) : Context, Context.StableIdContext
```

## Add a ramp-up rule

Define a boolean feature and add `rampUp { ... }` inside the rule.

```kotlin
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.enable

object RolloutFlags : Namespace("rollout") {
    val checkoutUpsell by boolean<RolloutContext>(default = false) {
        enable {
            rampUp { 25 }
        }
    }
}
```

This rule means matching users are eligible for the feature only when their
deterministic bucket falls inside the first 25 percent of the rollout.

## Inspect the result with `explain(...)`

`evaluate(...)` gives you the value. `explain(...)` gives you the same
decision plus the bucket metadata that led to it.

```kotlin
import io.amichne.konditional.api.explain
import io.amichne.konditional.core.id.StableId

val context = RolloutContext(stableId = StableId.of("same-user"))

val first = RolloutFlags.checkoutUpsell.explain(context)
val second = RolloutFlags.checkoutUpsell.explain(context)

check(first.value == second.value)
check(first.decision == second.decision)
```

The result stays stable for the same feature, same salt, and same stable ID.

## Next steps

The next page adds the JSON boundary on top of the same namespace model.

- [Load JSON safely](load-json-safely.md)

