# Konditional

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Codebase](https://github.com/amichne/konditional/actions/workflows/ci.yml/badge.svg)](https://github.com/amichne/konditional/actions/workflows/ci.yml)

Konditional is a Kotlin feature-flag library for teams that want typed,
deterministic evaluation without string keys, silent coercion, or global flag
registries. You define flags as Kotlin properties inside a namespace,
evaluate them against typed contexts, and keep compile-time guarantees visible
at the call site.

## Why engineers reach for it

Konditional is useful when feature flags need to stay readable and trustworthy
as they become part of real product logic.

- **Typed values stay typed.** `enum`, `boolean`, `integer`, and `custom`
  definitions evaluate to the same types you declared.
- **Rules stay in code.** Teams can read and review the evaluation logic beside
  the namespace that owns it.
- **Evaluation stays deterministic.** The same context and the same snapshot
  produce the same result.
- **Runtime updates stay atomic.** Namespace snapshots swap as whole units.
- **Ownership stays local.** Separate namespaces isolate teams and prevent
  cross-namespace drift.

## A quick example

The fastest way to see the difference is with a typed eligibility flag instead
of another boolean.

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
    CheckoutFlags.productEligibility.evaluate(CommerceContext(Product.PAYMENTS))
```

The result is a `ProductEligibility`, not a `Boolean` or a string lookup.
Callers can use an exhaustive `when` and keep the domain states explicit.

## Start small in a monorepo

Start with one namespace inside a team-owned Gradle module. Keep the flag
definition, its domain enums, and the first caller together. If another team
later needs the same contract, move the shared namespace or shared types into
a common module then. Start with one namespace inside a team-owned module,
sharing via a common module only if required, but keep the initial surface
focused where possible.

For a minimal evaluation path, depend on `konditional-engine` in that module.

```kotlin
dependencies {
    implementation("io.amichne.konditional:konditional-engine:<version>")
}
```

Pull `konditional-json` later when you are ready to parse external snapshots.
You do not need it to declare flags and call `evaluate(...)`.

## Learn by path

The documentation is organized around evaluation, adoption, and trustworthy
runtime behavior.

- [Home](docs/index.md)
- [Start here](docs/start-here/index.md)
- [Quickstart](docs/quickstart/index.md)

## Contributing and project docs

These links cover the project basics for contributors and evaluators.

- [Contributing guide](CONTRIBUTING.md)
- [License](LICENSE)
