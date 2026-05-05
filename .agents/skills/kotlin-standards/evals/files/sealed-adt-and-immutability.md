# Sealed ADTs and immutability

The current design models a closed workflow with strings, booleans, and mutable
state:

```kotlin
data class SyncState(
    var status: String,
    var retryable: Boolean,
    var errorMessage: String?,
)

fun nextState(
    current: SyncState,
    networkFailed: Boolean,
    userCancelled: Boolean,
): SyncState
```

Problems:

- `status`, `retryable`, and `errorMessage` can represent contradictory states.
- Callers need to know which string values are valid.
- Mutation hides which transitions are legal.
- The implementation uses mutable accumulators and nested conditionals where a
  closed set of typed variants would be clearer.

The expected guidance should favor sealed ADTs, exhaustive `when`, immutable
models, and Kotlin-native transformation style.
