# Kotlin Type-Safe Domain Modeling

Use this reference for value classes, state-encoding types, nullability removal, immutability, and other patterns that make invalid domain states hard to construct.

## Included sections

- Compile-Time Guarantees
- Nullability Elimination
- Immutability Patterns
- Type Aliases for Clarity

## Compile-Time Guarantees

### Phantom Types
Encode state transitions in the type system to prevent invalid operations at compile time.

```kotlin
sealed interface State
sealed interface Disconnected : State
sealed interface Connected : State
sealed interface Authenticated : State

class Client<S : State> private constructor() {
    companion object {
        fun create(): Client<Disconnected> = Client()
    }
    
    fun connect(): Client<Connected> = Client()
}

fun Client<Connected>.authenticate(): Client<Authenticated> = Client()
fun Client<Authenticated>.sendRequest(req: Request): Response = TODO()

// Compile error: cannot send request on disconnected client
// val client = Client.create()
// client.sendRequest(request) // Won't compile!

// Must follow the state progression
val client = Client.create()
    .connect()
    .authenticate()
    .sendRequest(request) // OK!
```

### Inline Value Classes
Zero-cost type safety for primitive values:

```kotlin
@JvmInline
value class UserId(val value: String)

@JvmInline
value class OrderId(val value: String)

// Compile error: cannot pass UserId where OrderId expected
fun getOrder(id: OrderId): Order

val userId = UserId("user-123")
getOrder(userId) // Won't compile!
```

### Type-Level Computation
Use the type system to enforce invariants:

```kotlin
// Non-empty list guaranteed at compile time
sealed interface NonEmptyList<out T> {
    val head: T
    val tail: List<T>
    
    data class Single<T>(override val head: T) : NonEmptyList<T> {
        override val tail: List<T> = emptyList()
    }
    
    data class Cons<T>(
        override val head: T,
        override val tail: NonEmptyList<T>
    ) : NonEmptyList<T>
}

// Functions that require non-empty lists can guarantee it
fun <T> max(list: NonEmptyList<T>): T where T : Comparable<T> {
    // No need to check if list is empty!
    return list.tail.fold(list.head) { acc, item ->
        if (item > acc) item else acc
    }
}
```

## Nullability Elimination

### Non-Null by Construction
Design APIs that make null impossible:

```kotlin
// Bad: Nullable return requires null checks
fun findUser(id: String): User?

// Good: Explicit outcome type
sealed interface UserLookup {
    data class Found(val user: User) : UserLookup
    data object NotFound : UserLookup
}

fun findUser(id: String): UserLookup
```

### Late Initialization Alternatives
Avoid lateinit with safer patterns:

```kotlin
// Bad: lateinit can throw
class Service {
    private lateinit var dependency: Dependency
    fun initialize(dep: Dependency) { dependency = dep }
}

// Good: Require in constructor
class Service(private val dependency: Dependency)

// Good: Use lazy for computed values
class Service {
    private val dependency: Dependency by lazy { createDependency() }
}

// Good: Explicit initialization state
class Service {
    private var dependency: Dependency? = null
    val isInitialized get() = dependency != null
    
    fun initialize(dep: Dependency) {
        check(dependency == null) { "Already initialized" }
        dependency = dep
    }
}
```

### Optional vs Null
Use explicit Option/Maybe type when nullability has semantic meaning:

```kotlin
sealed interface Option<out T> {
    data class Some<T>(val value: T) : Option<T>
    data object None : Option<Nothing>
}

// Makes "no value" explicit in the API
fun getConfig(key: String): Option<String>
```

## Immutability Patterns

### Data Class Immutability
All fields should be val, not var:

```kotlin
// Good: Immutable data class
data class User(
    val id: UserId,
    val name: String,
    val email: String
)

// Use copy for modifications
val updated = user.copy(name = "New Name")

// Bad: Mutable data class
data class User(
    var id: UserId,
    var name: String,
    var email: String
)
```

### Immutable Collections
Expose read-only collection interfaces:

```kotlin
// Good: Return immutable interface
class Repository {
    private val items = mutableListOf<Item>()
    
    fun getItems(): List<Item> = items.toList() // Defensive copy
    // Or: return items.asUnmodifiable() if copying is expensive
}

// Bad: Expose mutable collection
class Repository {
    val items = mutableListOf<Item>() // Direct access!
}
```

### Builder Pattern for Complex Objects
Use builders for objects with many optional fields:

```kotlin
data class Config private constructor(
    val host: String,
    val port: Int,
    val timeout: Duration,
    val retries: Int,
    val useSsl: Boolean
) {
    class Builder {
        private var host: String? = null
        private var port: Int = 8080
        private var timeout: Duration = 30.seconds
        private var retries: Int = 3
        private var useSsl: Boolean = true
        
        fun host(value: String) = apply { host = value }
        fun port(value: Int) = apply { port = value }
        fun timeout(value: Duration) = apply { timeout = value }
        fun retries(value: Int) = apply { retries = value }
        fun useSsl(value: Boolean) = apply { useSsl = value }
        
        fun build(): Config {
            val host = checkNotNull(host) { "host required" }
            require(port > 0) { "port must be positive" }
            return Config(host, port, timeout, retries, useSsl)
        }
    }
    
    companion object {
        fun builder() = Builder()
    }
}
```

## Type Aliases for Clarity

### Semantic Type Names
```kotlin
typealias UserId = String
typealias Timestamp = Long
typealias Json = String

// Makes intent clear
fun getUser(id: UserId): User
fun parseEvent(json: Json, timestamp: Timestamp): Event

// Better than
fun getUser(id: String): User
fun parseEvent(json: String, timestamp: Long): Event
```

### Complex Type Simplification
```kotlin
typealias UserCache = Map<UserId, User>
typealias EventHandler = (Event) -> Unit
typealias ValidationRule<T> = (T) -> Boolean

// Simpler signatures
fun cacheUsers(cache: UserCache, users: List<User>)
fun registerHandler(handler: EventHandler)
fun validate(value: String, rule: ValidationRule<String>): Boolean
```
