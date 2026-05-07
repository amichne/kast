# Define your first namespace

The best first flag is a typed decision that already matters to the business.
This page defines a product eligibility result, the context it depends on, and
the namespace that owns the rule.

## Model one axis and one return type

Start with an enum for the context dimension and an enum for the evaluated
result. Returning a domain enum is the easiest way to see the benefit of typed
features immediately.

```kotlin
import io.amichne.konditional.context.axis.AxisValue

enum class Product : AxisValue<Product> {
    PAYMENTS,
    LENDING,
    REPORTING,
}

enum class ProductEligibility {
    NOT_ELIGIBLE,
    ELIGIBLE,
    MANUAL_REVIEW,
}
```

## Build the minimal context

The base `Context` interface is intentionally small. For this first namespace,
the context only needs to expose the product axis.

```kotlin
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.axes

data class CommerceContext(
    val product: Product,
) : Context {
    override val axes = axes(product)
}
```

## Declare the namespace

Define the namespace in the same module that owns the feature. The namespace
is where the feature key, the return type, and the rule set come together.

```kotlin
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.rules.targeting.scopes.constrain

object CheckoutFlags : Namespace("checkout") {
    val productEligibility by enum<ProductEligibility, CommerceContext>(
        default = ProductEligibility.NOT_ELIGIBLE,
    ) {
        rule(ProductEligibility.ELIGIBLE) {
            constrain(Product.PAYMENTS, Product.REPORTING)
        }
        rule(ProductEligibility.MANUAL_REVIEW) {
            constrain(Product.LENDING)
        }
    }
}
```

At this point the namespace compiles and is immediately evaluable. You do not
need a separate runtime load step to begin using it.

## Next steps

The next page moves from declaration to evaluation and shows how the caller
sees a typed value instead of a string-key lookup.

- [Evaluate a typed flag](evaluate-a-typed-flag.md)

