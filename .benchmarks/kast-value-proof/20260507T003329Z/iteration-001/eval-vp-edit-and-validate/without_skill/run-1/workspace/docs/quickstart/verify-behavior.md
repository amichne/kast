# Verify behavior

The quickstart is complete once you have checked the behaviors that make
Konditional different from a plain string-key system. This page gives you a
small verification checklist.

## Verify typed evaluation

Confirm that the call site sees the real domain type you declared on the
namespace.

```kotlin
val result: ProductEligibility =
    CheckoutFlags.productEligibility.evaluate(
        CommerceContext(Product.PAYMENTS),
    )
```

If that type annotation feels natural in the caller, the basic model is
already paying off.

## Verify deterministic rollout

Run `explain(...)` twice for the same stable ID and confirm the decision and
bucket metadata do not drift.

```kotlin
val context = RolloutContext(stableId = StableId.of("same-user"))
check(RolloutFlags.checkoutUpsell.explain(context) ==
    RolloutFlags.checkoutUpsell.explain(context))
```

## Verify strict boundary behavior

Pass malformed JSON into `fromJson(json)` and confirm you receive
`ParseResult.Failure` instead of a partially loaded namespace.

```kotlin
check(CheckoutFlags.fromJson("{ not-json") is ParseResult.Failure)
```

## Verify namespace isolation

Declare the same feature key in two different namespaces and confirm their
defaults or loaded snapshots do not bleed into each other.

That isolation is what lets one team adopt Konditional without immediately
creating a shared global registry.

## Next steps

Once the quickstart feels solid, move into the concept pages or jump to the
guides for specific scenarios like custom values, remote configuration, and
migration.

- [Read the concepts](../concepts/index.md)
- [Read the guides](../guides/index.md)
