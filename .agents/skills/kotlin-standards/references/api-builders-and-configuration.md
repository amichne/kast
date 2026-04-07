# Kotlin API Builders and Configuration

Use this reference for builder-style APIs, receiver lambdas, immutable configuration objects, and the implementation details that make builder entry points safe and ergonomic.

## Included sections

- 3. Lambda with Receiver (`Type.() -> Unit`)
- 4. DSL Builder (`build*` + `Builder` class)
- 7. `Configuration` Data Class
- 11. `@PublishedApi internal` vs `internal` Constructor
- 13. `inline` and Kotlin Contracts

## 3. Lambda with Receiver (`Type.() -> Unit`)

Use a lambda with receiver when:

1. **Configuring an existing object or scope** from outside (not constructing a new one)
2. **The block is called exactly once** (use `contract { callsInPlace(block, EXACTLY_ONCE) }`)
3. **Caller needs access to `this`** inside the block (e.g., call methods, set properties)
4. **One level of nesting** — the block itself does not take further builder lambdas

<details><summary>Reasoning</summary>

The receiver type `T` in `T.() -> Unit` provides the caller with implicit `this` — all properties and methods of `T` are directly accessible without qualification. This is the pattern used by `buildString { append("…") }`, `apply { … }`, and all Ktor plugin installers. The `callsInPlace(EXACTLY_ONCE)` contract is required so the compiler can prove the block runs, enabling `val` definite assignment, smart casts, and null-safety inference inside the block — without it, the compiler assumes the lambda may not execute at all. The single-nesting constraint prevents two receivers from being simultaneously in scope, which would cause `@DslMarker` to be required at multiple levels and confuse IDE autocomplete.

</details>

### Examples from canonical libraries

```kotlin
// ✅ Kotlin stdlib — buildString configures a StringBuilder, returns immutable String
inline fun buildString(builderAction: StringBuilder.() -> Unit): String {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return StringBuilder().apply(builderAction).toString()
}

// ✅ kotlinx.serialization — Json { } configures a sealed Json instance
fun Json(from: Json = Json.Default, builderAction: JsonBuilder.() -> Unit): Json

// ✅ Ktor — HttpClient { } configures the HTTP engine and plugins
val client = HttpClient(CIO) {
    install(ContentNegotiation) { json() }
    defaultRequest { url("https://api.example.com") }
}

// ✅ MCP SDK — Server configures itself in the init block
class Server(..., block: Server.() -> Unit = {}) {
    init { block(this) }
}
```

### The `@DslMarker` rule for receivers

Any class that appears as a DSL receiver **must** be annotated with `@McpDsl` (or equivalent `@DslMarker`).
This prevents implicit access to outer scopes — a subtle source of bugs in nested DSLs:

```kotlin
@DslMarker
annotation class McpDsl

@McpDsl
class CallToolRequestBuilder { ... }
```

If a class is used as a receiver but not annotated with `@DslMarker`, it allows
calling methods from an outer builder's `this` inside the inner block — a silent bug.

<details><summary>Reasoning</summary>

Without `@DslMarker`, Kotlin's implicit `this` resolution can silently dispatch a call to the _outer_ receiver when the inner receiver does not have the method. For example, inside a `BarBuilder` block nested inside a `FooBuilder` block, an unqualified `fooMethod()` call compiles successfully but modifies the outer `FooBuilder` — a completely unintended side-effect. The `@DslMarker` annotation causes the compiler to reject any unqualified call that would resolve through an outer `this`, turning the silent bug into a compile error. Real-world examples include `@HtmlTagMarker` in the Kotlin HTML DSL and `@KtorDsl` in Ktor, both of which enforce this boundary explicitly.

</details>

### `@RestrictsSuspension` for coroutine-scope builders

When a lambda with receiver is used inside a coroutine builder where only a specific
set of `suspend` functions should be callable (not arbitrary suspension), annotate the
receiver class with `@RestrictsSuspension`:

```kotlin
// ✅ Kotlin stdlib — only yield/yieldAll can be called inside sequence { }
@RestrictsSuspension
public abstract class SequenceScope<in T> internal constructor() {
    abstract suspend fun yield(value: T)
    abstract suspend fun yieldAll(iterator: Iterator<T>)
}

// Usage — calling delay() or other arbitrary suspend functions inside is a compile error
val fibs = sequence {
    yield(0); yield(1)
    // delay(100) ← compile error: restricted suspension
}
```

Use `@RestrictsSuspension` when the lambda's receiver restricts which suspend functions
are legal to call. This prevents accidental misuse of the builder scope as a general
coroutine scope.

<details><summary>Reasoning</summary>

The Kotlin stdlib's `SequenceScope` (`stdlib/src/kotlin/collections/SequenceBuilder.kt`) is annotated `@RestrictsSuspension` and its KDoc states exactly the reason: "restricted when used as receivers for extension `suspend` functions — can only invoke other member or extension `suspend` functions on this particular receiver and are restricted from calling arbitrary suspension functions." Without this annotation, a caller could write `delay(100)` inside `sequence { }`, which silently violates the lazy evaluation contract — sequences execute on a synchronous continuation that cannot suspend arbitrarily. The annotation turns that misuse into a compile-time error.

</details>

---

## 4. DSL Builder (`build*` + `Builder` class)

Use a DSL builder when **any** of the following hold:

1. **≥ 3 independently settable properties**, where at least one is optional
2. **Nested sub-builders** — a property itself requires a lambda to configure
3. **Mixed required and optional fields** that must be validated at `build()` time
4. **The constructed object is stored** (assigned to a `val` or passed to multiple callers)

<details><summary>Reasoning</summary>

The canonical model is `buildList` in the Kotlin stdlib (`stdlib/src/kotlin/collections/Collections.kt`): an `inline` top-level function with `contract { callsInPlace(builderAction, EXACTLY_ONCE) }` that applies a `MutableList.() -> Unit` lambda and returns an immutable `List`. The same pattern appears in `buildJsonObject`/`buildJsonArray` in kotlinx.serialization. The threshold "≥ 3 independently settable properties, at least one optional" reflects where named arguments stop being self-labelling: each `var name = value` assignment in a builder block is inherently self-documenting and IDE-guided, while a 7-argument named call requires the reader to scan the full list. Nested sub-builders (condition 2) make named-argument style structurally impossible — a lambda cannot be a default argument value. Stored objects (condition 4) warrant a builder because they are typically inspected after construction, which requires a stable type rather than a transient parameter list.

</details>

### Structure rules

```
buildFoo { ... }               ← inline top-level function, uses contract
    └── FooBuilder             ← annotated @McpDsl, @PublishedApi internal constructor
            ├── var requiredField: Type? = null
            ├── var optionalField: Type? = null
            ├── fun subBuilder(block: BarBuilder.() -> Unit)   ← delegates to BarBuilder
            └── @PublishedApi internal fun build(): Foo        ← validates, constructs
```

<details><summary>Reasoning</summary>

The `@PublishedApi` annotation is defined in `stdlib/src/kotlin/Annotations.kt` with this KDoc: "Public inline functions cannot use non-public API, since if they are inlined, those non-public API references would violate access restrictions at a call site. To overcome this restriction an `internal` declaration can be annotated with `@PublishedApi`." The builder constructor must be `@PublishedApi internal` (not `public`) because: (1) it prevents callers from instantiating the builder directly — they must go through the `buildFoo { }` entry point; (2) the `inline` entry function can still call it after inlining at the call site. The stdlib's own `HexFormat.Builder` (`stdlib/src/kotlin/text/HexFormat.kt`) uses exactly this pattern: `public class Builder @PublishedApi internal constructor()`.

</details>

### Examples from canonical libraries

```kotlin
// ✅ Kotlin stdlib — buildList is the canonical DSL builder pattern
inline fun <E> buildList(builderAction: MutableList<E>.() -> Unit): List<E> {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return mutableListOf<E>().apply(builderAction)
}

// ✅ kotlinx.serialization — buildJsonObject follows the same structure
inline fun buildJsonObject(builderAction: JsonObjectBuilder.() -> Unit): JsonObject {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    return JsonObjectBuilder().apply(builderAction).build()
}

// ✅ MCP SDK — top-level inline entry point following the same convention
@OptIn(ExperimentalContracts::class)
@ExperimentalMcpApi
inline fun buildCallToolRequest(block: CallToolRequestBuilder.() -> Unit): CallToolRequest {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return CallToolRequestBuilder().apply(block).build()
}

// ✅ Builder with required + optional fields
@McpDsl
class CallToolRequestBuilder @PublishedApi internal constructor() : RequestBuilder() {
    var name: String? = null       // required — validated in build()

    fun arguments(block: JsonObjectBuilder.() -> Unit)   // optional, sub-builder
    fun arguments(arguments: JsonObject)                  // plain overload

    @PublishedApi
    override fun build(): CallToolRequest {
        val name = requireNotNull(name) { "Missing required field 'name'..." }
        return CallToolRequest(CallToolRequestParams(name = name, ...))
    }
}
```

### Require-vs-optional convention

| Field state | How to declare | How to validate |
|---|---|---|
| Required | `var field: Type? = null` | `requireNotNull(field) { "Missing..." }` in `build()` |
| Optional with default | `var field: Type = default` | No validation needed |
| Optional nullable | `var field: Type? = null` | Pass through as `null` |

---

## 7. `Configuration` Data Class

Canonical Kotlin libraries distinguish three separate patterns for optional parameters. The choice
is **not** driven by count — it depends on whether the configuration needs to survive past the
constructor call.

### Three patterns and when to use each

| Pattern | Use when | Canonical examples |
|---|---|---|
| **Named parameters** | Options consumed at construction; not stored or exposed afterward | `MutableSharedFlow`, `Channel` |
| **Immutable `FooConfiguration` object** | Config is stored on the object and exposed read-only for diagnostics/inspection | `JsonConfiguration`, `CborConfiguration` |
| **Mutable DSL builder class** | Config is expressed inside a plugin `install { }` block, used in-place then discarded | Ktor `WebSocketOptions` |

<details><summary>Reasoning</summary>

`MutableSharedFlow(replay, extraBufferCapacity, onBufferOverflow)` uses named parameters because the options drive internal buffer setup and are never exposed publicly — there is nothing to inspect after construction. `Json` uses `JsonConfiguration` (stored as `val configuration: JsonConfiguration` on the sealed class) because callers legitimately inspect `json.configuration.encodeDefaults` after construction, for example inside custom serializers via `JsonDecoder` and `JsonEncoder`. Ktor's `WebSocketOptions` uses a mutable builder class because it is only meaningful inside the `install(WebSockets) { … }` block and is discarded after the plugin is configured — storing it would waste memory and expose a mutable object after its window of use.

</details>

### When to prefer `Configuration` over named arguments

| Named parameters | Immutable `FooConfiguration` object |
|---|---|
| Options consumed at construction only | Config **stored** on the created object (`val configuration: JsonConfiguration`) |
| Options not accessible after creation | Config exposed read-only for logging, comparison, or diagnostics |
| Options don't form an inspectable unit | Options form one cohesive, inspectable configuration object |

The parameter **count is not the trigger**. `MutableSharedFlow` has 3 named parameters and no
`Configuration` class; `JsonConfiguration` has 17 properties and is stored on `Json` because
callers may inspect `json.configuration.encodeDefaults` after construction.

<details><summary>Reasoning</summary>

`JsonConfiguration` (`formats/json/commonMain/src/…/JsonConfiguration.kt`) has 17 `val` properties and an `internal constructor` — it cannot be instantiated externally. It is stored on `sealed class Json(val configuration: JsonConfiguration, …)` and its KDoc states: "Can be used for debug purposes and for custom Json-specific serializers via `JsonEncoder` and `JsonDecoder`." This is the concrete evidence that inspectability after construction — not count — is the deciding factor. Counting parameters and picking a pattern based on that number alone would have given the wrong answer here.

</details>

### Named parameters — `MutableSharedFlow` (kotlinx.coroutines)

```kotlin
// 3 named params with defaults — no Configuration class.
// Options drive internal buffer setup and are NOT stored publicly.
@Suppress("FunctionName")
public fun <T> MutableSharedFlow(
    replay: Int = 0,
    extraBufferCapacity: Int = 0,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
): MutableSharedFlow<T> = ...

// Channel follows the same pattern
public fun <E> Channel(
    capacity: Int = Channel.RENDEZVOUS,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    onUndeliveredElement: ((E) -> Unit)? = null,
): Channel<E> = ...
```

### Immutable configuration object — `JsonConfiguration` (kotlinx.serialization)

```kotlin
// 17 val properties — internal constructor prevents external instantiation.
// Stored on the format object: sealed class Json(val configuration: JsonConfiguration, ...)
public class JsonConfiguration internal constructor(
    public val encodeDefaults: Boolean = false,
    public val ignoreUnknownKeys: Boolean = false,
    public val isLenient: Boolean = false,
    // ... 14 more val properties ...
) {
    override fun toString(): String = "JsonConfiguration(...)"  // for diagnostics
}

// Paired with a mutable builder used only inside the DSL block
public class JsonBuilder internal constructor(json: Json) {
    public var encodeDefaults: Boolean = json.configuration.encodeDefaults
    public var ignoreUnknownKeys: Boolean = json.configuration.ignoreUnknownKeys
    // ...
    internal fun build(): JsonConfiguration = JsonConfiguration(encodeDefaults, ...)
}

// Usage
val json = Json { ignoreUnknownKeys = true }
val cfg: JsonConfiguration = json.configuration  // inspectable after construction
```

### Mutable DSL builder — `WebSocketOptions` (Ktor)

```kotlin
// var properties — builder used in-place, NOT stored on the plugin object afterward.
@KtorDsl
public class WebSocketOptions {
    public var pingPeriodMillis: Long = PINGER_DISABLED
    public var timeoutMillis: Long = 15_000L
    public var maxFrameSize: Long = Long.MAX_VALUE
    public var masking: Boolean = false
    public var contentConverter: WebsocketContentConverter? = null
}

// The install block mutates the builder directly; it is not retained afterward.
install(WebSockets) {
    pingPeriodMillis = 5_000L
    timeoutMillis = 30_000L
}
```

### Migration path: deprecating flat parameters

When flat parameters are outgrown, deprecate the old constructor with `@Deprecated` and a
`replaceWith`:

```kotlin
@Deprecated(
    "Use constructor with Configuration",
    // ReplaceWith requires real parameter names to be IDE-applicable; fill them in:
    replaceWith = ReplaceWith(
        "MyClass(MyClass.Configuration(optionA = optionA, optionB = optionB))"
    ),
)
constructor(optionA: Boolean = false, optionB: String = "")
    : this(Configuration(optionA, optionB))
```

This gives callers a migration path without breaking existing code.

<details><summary>Reasoning</summary>

`JsonConfiguration` itself demonstrates the consequence of not providing a migration path: its `classDiscriminatorMode` property carries `@set:Deprecated(…, level = DeprecationLevel.ERROR)` with the message "JsonConfiguration is not meant to be mutable, and will be made read-only in a future release. The `Json(from = …) {}` copy builder should be used instead." Without a `@Deprecated` + `replaceWith`, callers have no IDE-guided migration and both the old and new APIs silently coexist — creating confusion about which is canonical. The `ReplaceWith` expression must contain real parameter names to be IDE-applicable (the IDE can auto-apply it).

</details>

---

## 11. `@PublishedApi internal` vs `internal` Constructor

Both hide the builder constructor from public use, but the choice depends on whether the factory is `inline`:

| Factory is `inline` | Constructor visibility |
|---|---|
| Yes (most DSL builders) | `@PublishedApi internal constructor` — the `inline` function must access internal members after inlining |
| No (heavyweight factories like `Json { }`) | Plain `internal constructor` — no inlining occurs, `@PublishedApi` is unnecessary |

<details><summary>Reasoning</summary>

The stdlib's `@PublishedApi` KDoc (`stdlib/src/kotlin/Annotations.kt`) states the exact rule: "Public inline functions cannot use non-public API, since if they are inlined, those non-public API references would violate access restrictions at a call site." When `buildFoo { }` is `inline`, the compiler copies its body — including the `FooBuilder()` constructor call — into the caller's module. If the constructor is plain `internal`, the caller's module cannot access it after inlining, causing a compilation error. `@PublishedApi` makes `internal` declarations accessible at inline call sites while keeping them hidden from non-inline usage. `JsonBuilder` uses plain `internal constructor` because `fun Json(…)` is not `inline` — no inlining occurs, so `@PublishedApi` is unnecessary overhead.

</details>

```kotlin
// ✅ inline factory → @PublishedApi internal constructor
inline fun buildCallToolRequest(block: CallToolRequestBuilder.() -> Unit): CallToolRequest {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return CallToolRequestBuilder().apply(block).build()   // needs @PublishedApi
}
class CallToolRequestBuilder @PublishedApi internal constructor()

// ✅ non-inline factory (Json-style) → plain internal constructor
fun Json(from: Json = Json.Default, builderAction: JsonBuilder.() -> Unit): Json {
    val builder = JsonBuilder(from)
    builder.builderAction()                                // no inlining — plain internal is fine
    return JsonImpl(builder.build(), builder.serializersModule)
}
class JsonBuilder internal constructor(json: Json)
```

---

## 13. `inline` and Kotlin Contracts

Every **top-level DSL entry function** should be `inline` with a contract:

```kotlin
@OptIn(ExperimentalContracts::class)
inline fun buildFoo(block: FooBuilder.() -> Unit): Foo {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return FooBuilder().apply(block).build()
}
```

**Why `inline`:** Avoids lambda object allocation. Also required so that `@PublishedApi internal`
members of the builder (constructor, `build()`) are accessible at the call site after inlining.

**Why `contract`:** Lets the compiler know the lambda runs exactly once, enabling:
- Definite assignment of `val` variables inside the block
- Smart casts that survive the block
- Better null-safety analysis

`contract` is **not** restricted to `inline` functions — `coroutineScope`, `supervisorScope`,
and `withContext` all use `contract { callsInPlace(block, EXACTLY_ONCE) }` without being `inline`.
What `inline` adds is allocation-free lambda passing and `@PublishedApi` access.

Do **not** add `contract` to builder methods that may be called zero or more times.

<details><summary>Reasoning</summary>

`buildList` in `stdlib/src/kotlin/collections/Collections.kt` is the canonical reference: it is `inline` and declares `contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }`. The `inline` keyword eliminates the lambda object allocation on every call — for DSL builders called frequently (e.g., building protocol messages in a tight loop), this avoids garbage pressure. The `contract` is what allows `val x: String; buildList { x = "hello" }; println(x)` to compile — without it, the compiler cannot prove `x` is initialised after the block. `coroutineScope { }` in kotlinx.coroutines uses the same contract without being `inline`, demonstrating that the two features are independent: `inline` is about allocation; `contract` is about compiler flow analysis.

</details>

---
