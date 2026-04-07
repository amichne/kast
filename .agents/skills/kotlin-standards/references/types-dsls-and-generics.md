# Kotlin Type-Safe DSLs and Generics

Use this reference for DSL scoping, variance, reified generics, and context receivers when the design question is about compile-time guarantees in expressive APIs.

## Included sections

- Type-Safe DSLs
- Variance and Type Bounds
- Reified Generics
- Context Receivers (Experimental)

## Type-Safe DSLs

### Scope Control with Receivers
Use scoped functions to create type-safe configuration DSLs:

```kotlin
class ConfigBuilder {
    var timeout: Duration = 30.seconds
    var retries: Int = 3
    
    fun build() = Config(timeout, retries)
}

fun config(block: ConfigBuilder.() -> Unit): Config {
    return ConfigBuilder().apply(block).build()
}

// Usage
val cfg = config {
    timeout = 60.seconds
    retries = 5
}
```

### Marker Interfaces for DSL Scoping
Prevent invalid nesting:

```kotlin
@DslMarker
annotation class HtmlDsl

@HtmlDsl
interface Element {
    fun render(): String
}

@HtmlDsl
class Html : Element {
    private val children = mutableListOf<Element>()
    
    fun body(block: Body.() -> Unit) {
        children.add(Body().apply(block))
    }
    
    override fun render() = children.joinToString { it.render() }
}

@HtmlDsl
class Body : Element {
    private val children = mutableListOf<Element>()
    
    fun div(block: Div.() -> Unit) {
        children.add(Div().apply(block))
    }
    
    override fun render() = "<body>${children.joinToString { it.render() }}</body>"
}

// DSL marker prevents invalid nesting
html {
    body {
        div {
            // body { } // Compile error! Can't nest body in div
        }
    }
}
```

## Variance and Type Bounds

### Covariance for Producers
Use `out` for types that only produce values:

```kotlin
interface Producer<out T> {
    fun produce(): T
    // Cannot have methods that consume T
}

val stringProducer: Producer<String> = TODO()
val anyProducer: Producer<Any> = stringProducer // OK, covariant
```

### Contravariance for Consumers
Use `in` for types that only consume values:

```kotlin
interface Consumer<in T> {
    fun consume(value: T)
    // Cannot have methods that produce T
}

val anyConsumer: Consumer<Any> = TODO()
val stringConsumer: Consumer<String> = anyConsumer // OK, contravariant
```

### Type Bounds for Constraints
Constrain generic types to ensure required capabilities:

```kotlin
// Upper bound
fun <T : Comparable<T>> max(a: T, b: T): T = if (a > b) a else b

// Multiple bounds
interface Persistable {
    fun save()
}

fun <T> persist(item: T) where T : Serializable, T : Persistable {
    // T is both Serializable and Persistable
}
```

## Reified Generics

### Type-Safe Casting
Use reified generics to access type information at runtime:

```kotlin
inline fun <reified T> JsonNode.parse(): T {
    return when (T::class) {
        String::class -> this.asText() as T
        Int::class -> this.asInt() as T
        else -> objectMapper.treeToValue(this, T::class.java)
    }
}

// Usage
val value: String = jsonNode.parse() // Type-safe!
```

### Instance Checks
```kotlin
inline fun <reified T> Any.isInstanceOf(): Boolean = this is T

val obj: Any = "Hello"
obj.isInstanceOf<String>() // true
obj.isInstanceOf<Int>() // false
```

## Context Receivers (Experimental)

### Type-Safe Context Propagation
```kotlin
interface LoggingContext {
    fun log(message: String)
}

interface DatabaseContext {
    fun query(sql: String): ResultSet
}

// Function requires both contexts
context(LoggingContext, DatabaseContext)
fun fetchUser(id: String): User {
    log("Fetching user $id")
    val result = query("SELECT * FROM users WHERE id = ?")
    return parseUser(result)
}

// Must be called with both contexts
with(loggingCtx) {
    with(dbCtx) {
        fetchUser("123")
    }
}
```
