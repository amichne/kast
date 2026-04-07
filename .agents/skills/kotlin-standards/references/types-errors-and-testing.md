# Kotlin Type Safety Error Design and Testing

Use this reference for typed outcomes, anti-pattern checks, and techniques that prove type-level guarantees through tests.

## Included sections

- Error Handling Without Wrappers
- Anti-Patterns to Avoid
- Testing Type Safety

## Error Handling Without Wrappers

### Sealed Class Outcomes
Represent success/failure as part of the domain, not as a wrapper type:

```kotlin
// Good: Domain-specific outcomes
sealed interface EvaluationOutcome<out T> {
    data class Enabled<T>(val value: T) : EvaluationOutcome<T>
    data object Disabled : EvaluationOutcome<Nothing>
    // No error case exposed - handled internally
}

// Bad: Generic wrapper exposes errors
sealed interface Result<out T, out E> {
    data class Success<T>(val value: T) : Result<T, Nothing>
    data class Failure<E>(val error: E) : Result<Nothing, E>
}
```

### Internal Error Containment
Errors should be handled internally and not leak to consumers:

```kotlin
class FeatureFlags internal constructor(
    private val config: Config,
    private val evaluator: Evaluator
) {
    // All errors caught and converted to Disabled
    fun evaluate(feature: String): EvaluationOutcome<Config> {
        return try {
            when (val result = evaluator.evaluate(feature, config)) {
                is InternalSuccess -> Enabled(result.config)
                is InternalFailure -> {
                    logger.error("Evaluation failed", result.error)
                    Disabled
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error", e)
            Disabled
        }
    }
}

// Internal error types never exposed
private sealed interface InternalResult
private data class InternalSuccess(val config: Config) : InternalResult
private data class InternalFailure(val error: Throwable) : InternalResult
```

### Exhaustive When Expressions
Use sealed classes to ensure all cases are handled:

```kotlin
sealed interface Message {
    data class Text(val content: String) : Message
    data class Image(val url: String) : Message
    data class Video(val url: String) : Message
}

fun handle(message: Message): Unit = when (message) {
    is Message.Text -> handleText(message.content)
    is Message.Image -> handleImage(message.url)
    is Message.Video -> handleVideo(message.url)
    // Compiler enforces exhaustiveness - no else needed
}
```

## Anti-Patterns to Avoid

### Unchecked Casts
```kotlin
// Bad: Unsafe cast
val user = obj as User

// Good: Safe cast with handling
val user = obj as? User ?: return null
```

### Any Abuse
```kotlin
// Bad: Loss of type information
fun process(data: Any): Any

// Good: Generic with constraints
fun <T : Processable> process(data: T): T
```

### Reflection for Type Checking
```kotlin
// Bad: Runtime type checking
if (obj::class == User::class) { }

// Good: Use sealed classes or is checks
when (obj) {
    is User -> handleUser(obj)
    is Admin -> handleAdmin(obj)
}
```

### Nullable Types When Not Needed
```kotlin
// Bad: Null for "not found" semantics
fun find(id: String): User?

// Good: Explicit result type
sealed interface FindResult {
    data class Found(val user: User) : FindResult
    data object NotFound : FindResult
}
fun find(id: String): FindResult
```

## Testing Type Safety

### Compile-Time Test
```kotlin
// Test that certain operations don't compile
// Place in separate source set that's expected to fail compilation

fun testCannotSendOnDisconnected() {
    val client = Client.create()
    // client.sendRequest(request) // Should not compile
}

fun testCannotPassWrongId() {
    val userId = UserId("123")
    // getOrder(userId) // Should not compile
}
```

### Property-Based Testing
```kotlin
// Test type invariants hold for all inputs
@Test
fun `NonEmptyList always has at least one element`() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int())) { list ->
        assertTrue(list.size >= 1)
    }
}
```
