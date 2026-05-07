# Why Konditional

Konditional exists for teams that want feature flags to stay part of the type
system instead of becoming a side channel of string keys, ad hoc payload
shapes, and operational conventions that live outside the compiler.

## The problem it targets

String-key flag systems are easy to start and expensive to trust at scale. The
call site usually has to remember three things at once: the key name, the
expected value type, and the fallback semantics. That combination creates room
for silent mismatch.

```kotlin
val enabled = flags.getBoolean("checkout.upsell-enabled", fallback = false)
```

That call is compact, but the compiler cannot tell you whether the key exists,
whether the owning team renamed it, or whether another caller expects a
different type for the same runtime key.

## What Konditional changes

Konditional moves the feature definition into a Kotlin property on a
`Namespace`. That binds the key, the value type, and the owning namespace in
one place.

```kotlin
object CheckoutFlags : Namespace("checkout") {
    val upsellEnabled by boolean<CheckoutContext>(default = false)
}

val enabled = CheckoutFlags.upsellEnabled.evaluate(context)
```

The call site no longer guesses the key or the return type. It asks a typed
feature for a typed value.

## Why that matters in practice

The design pays off in three places that tend to hurt most in production.

- **At the call site.** `evaluate(...)` returns the declared Kotlin type, so
  downstream code can use exhaustive `when` expressions and avoid value-shape
  guesswork.
- **At ownership boundaries.** `Namespace` keeps one team's feature surface
  local until sharing is intentional.
- **At runtime boundaries.** JSON decode is explicit and strict, so malformed
  external input becomes a typed failure instead of a partially applied state.

## What this does not promise

Konditional does not turn every domain invariant into a compile-time proof.
Some supporting value objects still enforce constraints with runtime checks,
and external input is still untrusted until it passes through the parse
boundary. The gain is that normal feature evaluation stays typed and the
boundary behavior stays explicit.

## Next steps

If the problem statement matches what your team is struggling with, the next
question is whether the current shape of the library fits your environment.

- [See when Konditional fits](when-it-fits.md)
- [Jump to the quickstart](../quickstart/index.md)

