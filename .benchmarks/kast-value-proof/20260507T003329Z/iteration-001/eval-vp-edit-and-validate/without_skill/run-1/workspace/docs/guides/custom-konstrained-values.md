# Custom Konstrained values

`Konstrained` is the contract for custom feature values that are not built-in
primitives or enums. Use it when the feature value is a real domain object and
you still want JSON round-tripping through `konditional-json`.

## Start with an object-backed value

Object-backed values are the most straightforward shape for custom payloads.

```kotlin
import io.amichne.konditional.core.types.Konstrained
import io.amichne.kontracts.dsl.of
import io.amichne.kontracts.dsl.schema

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffMs: Double = 1000.0,
    val enabled: Boolean = true,
    val mode: String = "exponential",
) : Konstrained.Object {
    val schema =
        schema {
            ::maxAttempts of { minimum = 1 }
            ::backoffMs of { minimum = 0.0 }
            ::enabled of { default = true }
            ::mode of { minLength = 1 }
        }
}
```

The schema is what lets the JSON layer reject malformed payloads instead of
constructing a half-trusted value.

## Use the value in a feature

Declare the feature with `custom<T, C>(...)` just like you would declare an
enum or a boolean feature.

```kotlin
object CheckoutFlags : Namespace("checkout") {
    val retryPolicy by custom<RetryPolicy, Context>(
        default = RetryPolicy(),
    )
}
```

## Other supported shapes

`Konstrained` also supports primitive-backed value classes, array-backed
values, and adapted primitive encodings such as `AsString` or `AsInt`. Reach
for those shapes when the wire form is a single primitive or a single list.

## Next steps

If your custom values depend on a domain dimension like environment or tenant,
the next guide shows how to model that dimension as an axis.

- [Read the custom axes guide](custom-axes.md)

