# Konditional

Konditional is a Kotlin-first feature-flag library for teams that want typed
evaluation, deterministic runtime behavior, and explicit trust boundaries.
You declare features as Kotlin properties inside a `Namespace`, evaluate them
against typed `Context` values, and add JSON loading later without turning the
core model into a string-key registry.

## Why teams reach for it

Konditional is a good fit when feature flags are part of real product logic
and you want the compiler, the type system, and the runtime model to stay
aligned.

- **Typed values stay typed.** Built-in primitives, enums, and `Konstrained`
  custom types evaluate to the same Kotlin types you declared.
- **Evaluation stays deterministic.** The same context and the same namespace
  snapshot produce the same result.
- **Ownership stays local.** Namespaces keep one team's flags out of another
  team's runtime surface until sharing is intentional.
- **Runtime updates stay atomic.** Loading, rollback, disable, and enable
  operate on whole namespace snapshots.
- **Boundary failures stay explicit.** JSON loading returns `ParseResult`
  values with typed `ParseError` failures instead of silent coercion.

## A small typed example

The fastest way to see the difference is with a typed eligibility result
instead of another boolean.

```kotlin
import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.AxisValue
import io.amichne.konditional.context.axis.axes
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.rules.targeting.scopes.constrain

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

data class CommerceContext(
    val product: Product,
) : Context {
    override val axes = axes(product)
}

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

val eligibility =
    CheckoutFlags.productEligibility.evaluate(
        CommerceContext(Product.PAYMENTS),
    )
```

The result is a `ProductEligibility`, not a string lookup or an untyped
payload. Callers can keep their domain states explicit and exhaustive.

## Choose your path

This documentation set is organized around the questions evaluators usually
ask first.

- [Start here](start-here/index.md) if you want to decide whether Konditional
  fits your team and constraints.
- [Quickstart](quickstart/index.md) if you want the smallest working path from
  install to safe JSON loading.
- [Concepts](concepts/index.md) if you want the mental model before coding.
- [Guides](guides/index.md) if you need targeted recipes beyond the first run.
- [Guarantees](guarantees/index.md) if you want the trust contracts backed by
  the current test suite.
- [Reference](reference/index.md) if you need terse API and format lookup.

## What this documentation covers

The current repo is the source of truth for this site. The pages map to the
published modules and the behaviors they expose today.

- `konditional-types` covers shared identifiers, contexts, parse results, and
  custom value contracts.
- `konditional-engine` covers namespaces, rule evaluation, diagnostics, and
  runtime registry behavior.
- `konditional-json` covers snapshot export, strict decode, and safe namespace
  loading through `ParseResult`.
- `smoke-test` is a verification module, not a published dependency surface.

## Next steps

The evaluator hub gives the clearest decision support. If you already know you
want to try the library, jump straight to the guided install and first
namespace.

- [Read the evaluator overview](start-here/index.md)
- [Start the quickstart](quickstart/index.md)
