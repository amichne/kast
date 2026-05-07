# Evaluate a typed flag

Once the namespace exists, evaluation is direct. The caller asks the feature
for a value and receives the declared Kotlin type back.

## Call `evaluate(...)`

Import the evaluation extension and pass the typed context you defined on the
previous page.

```kotlin
import io.amichne.konditional.api.evaluate

val paymentsContext = CommerceContext(product = Product.PAYMENTS)
val lendingContext = CommerceContext(product = Product.LENDING)

val paymentsEligibility: ProductEligibility =
    CheckoutFlags.productEligibility.evaluate(paymentsContext)

val lendingEligibility: ProductEligibility =
    CheckoutFlags.productEligibility.evaluate(lendingContext)
```

The compiler already knows the result type is `ProductEligibility`.

## Use the result like a normal domain value

The value is ready for an exhaustive `when` just like any other enum in your
application.

```kotlin
when (CheckoutFlags.productEligibility.evaluate(paymentsContext)) {
    ProductEligibility.ELIGIBLE -> showSelfServePath()
    ProductEligibility.MANUAL_REVIEW -> routeToOpsReview()
    ProductEligibility.NOT_ELIGIBLE -> hideExperience()
}
```

This is the main ergonomic win. The caller stays in domain code instead of
manually decoding runtime strings or booleans into business states.

## Next steps

The next page adds progressive delivery. That is where `stableId`,
deterministic bucketing, and `explain(...)` start to matter.

- [Add ramp-up](add-ramp-up.md)

