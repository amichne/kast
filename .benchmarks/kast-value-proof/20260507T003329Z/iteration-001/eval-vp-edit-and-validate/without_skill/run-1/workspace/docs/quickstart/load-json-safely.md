# Load JSON safely

The JSON layer is additive. You keep the same `Namespace` and the same typed
features, then add strict snapshot export and import through
`konditional-json`.

## Export a known-good snapshot

Import the JSON extensions and export the current namespace configuration.

```kotlin
import io.amichne.konditional.serialization.toJson

val json: String = CheckoutFlags.toJson()
```

`toJson()` is the easiest way to see the exact wire shape that the namespace
expects.

## Load a snapshot through `ParseResult`

Import `fromJson` and branch on the result instead of assuming success.

```kotlin
import io.amichne.konditional.core.result.ParseResult
import io.amichne.konditional.serialization.fromJson

when (val result = CheckoutFlags.fromJson(json)) {
    is ParseResult.Success -> {
        println("Loaded ${result.value.flags.size} flags")
    }
    is ParseResult.Failure -> {
        println(result.error.message)
    }
}
```

On success, the extension loads the decoded `Configuration` into the namespace
for you. On failure, the namespace keeps its previous state.

## Treat invalid input as boundary failure

Malformed JSON and unknown feature keys do not partially update the namespace.
They return `ParseResult.Failure` with a typed `ParseError`.

```kotlin
val failure = CheckoutFlags.fromJson("{ not-json")

check(failure is ParseResult.Failure)
```

That failure path is the contract. You decide how to log, retry, or reject the
payload, but the live namespace state remains coherent.

## Next steps

The last quickstart page turns the core guarantees into a small verification
checklist you can reuse during evaluation.

- [Verify behavior](verify-behavior.md)

