# Remote configuration

This guide shows the smallest safe pattern for external configuration delivery.
Konditional does not fetch remote data for you. Your application fetches a
JSON string and hands it to `fromJson(json)` at the boundary.

## Keep the boundary narrow

Treat remote delivery as a producer of `String`, not as a producer of trusted
flags. The trust boundary is the call to `fromJson(json)`.

```kotlin
import io.amichne.konditional.core.result.ParseResult
import io.amichne.konditional.serialization.fromJson

fun refresh(payload: String) {
    when (val result = CheckoutFlags.fromJson(payload)) {
        is ParseResult.Success -> logger.info("Loaded ${result.value.flags.size} flags")
        is ParseResult.Failure -> logger.warn("Rejected snapshot: ${result.error.message}")
    }
}
```

## Why this is the recommended shape

The namespace only mutates on success. That keeps fetch, retry, transport, and
logging concerns outside the core evaluation path while preserving strict state
transitions inside the namespace.

## Operational advice

Keep one namespace per ownership boundary, log the metadata version when it is
present, and prefer replacing whole snapshots over hand-editing individual
flags in memory.

## Next steps

If your remote snapshot needs structured values, the next guide shows how to
model them with `Konstrained`.

- [Read the custom value guide](custom-konstrained-values.md)

