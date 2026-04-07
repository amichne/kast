# Kotlin API Parameter Selection

Use this reference when choosing between plain parameters, named arguments, overloads, lambda flavours, and constructor or builder entry points.

## Included sections

- Quick Reference
- 1. Plain Method Parameters
- 2. Named Arguments with Default Values
- 5. Dual Overloads: Value + Lambda
- 6. Lambda Flavours
- 10. Constructor vs. Builder vs. Plain Function
- Summary: The Numbers and the Questions

## Quick Reference

| Pattern | Parameters | Required fields | Nesting | Reuse |
|---|---|---|---|---|
| Plain parameters | ≤ 3 | All required | None | Irrelevant |
| Named arguments + defaults | 2–6 | Mix of required/optional | None | Irrelevant |
| Immutable configuration object | — | All optional | None | Stored on object; exposed read-only for diagnostics |
| Configuration lambda (`T.() -> Unit`) | — | — | 1 level, single concern | Not stored |
| Factory lambda (`() -> T`) | — | — | None | New object per call |
| Factory lambda with context (`Ctx.() -> T`) | — | — | 1 level | New object per call |
| Escape-hatch lambda (`T.() -> Unit = {}`) | — | — | 1 level | Optional customization |
| Coroutine builder lambda (`suspend CoroutineScope.() -> T`) | — | — | Structured concurrency scope | Bounded by parent scope |
| Action lambda (`(A, B) -> R`) | — | — | None | Synchronous computation |
| DSL builder (`build*` + `*Builder`) | — | Mix + required | ≥ 2 levels _or_ ≥ 3 fields | Object is stored |
| Extension on framework type | — | — | Framework scope | Framework-bound |
| Mutable/read-only pair (`_backing` + `val prop`) | — | — | None | Hot stream / shared state |

---

## 1. Plain Method Parameters

Use plain parameters when **all three** conditions hold:

1. **≤ 3 parameters** after removing the trailing lambda (if any)
2. **All parameters are required** or have obvious, universal defaults
3. **Parameters are primitives or well-known SDK types** (no complex nesting)

<details><summary>Reasoning</summary>

At ≤ 3 required parameters, positional calls remain readable — the reader maps each argument to its parameter without names. Beyond 3, callers start making argument-ordering errors. The Kotlin stdlib confirms the threshold: `Channel(capacity, onBufferOverflow, onUndeliveredElement)` and `MutableSharedFlow(replay, extraBufferCapacity, onBufferOverflow)` both use exactly 3 named parameters without a `Configuration` class. The "all required or obvious defaults" and "primitives or SDK types" conditions prevent ambiguity from optional parameters and complex nesting, which respectively warrant named arguments (§2) or a DSL builder (§4).

</details>

### Representative examples

```kotlin
// ✅ Three meaningful inputs with one obvious default
fun registerAsset(
    uri: String,
    name: String,
    description: String,
    mimeType: String = "text/plain",
)

// ✅ One enum parameter — nothing to name or configure
fun remove(mode: RemovalMode): Boolean

// ✅ Atomic query — caller has all values, no builder needed
fun findSession(id: SessionId): Session?
```

### Counter-examples (do NOT use plain parameters)

```kotlin
// ❌ 7 parameters — even with defaults, call sites become unreadable
fun addTool(name, description, inputSchema, title, outputSchema, toolAnnotations, meta, handler)
// → Prefer named arguments with defaults (see §2) or a builder (see §3)

// ❌ Optional nullable parameters mixed with required ones cause confusion
fun Tool(name: String, inputSchema: ToolSchema, description: String? = null,
         outputSchema: ToolSchema? = null, title: String? = null, ...)
// → A dedicated builder makes required vs optional obvious
```

---

## 2. Named Arguments with Default Values

Use named arguments when **at least one** condition holds _and_ nesting is absent:

1. **2–6 parameters** with clear, independent semantics
2. **Some parameters are optional** with reasonable defaults
3. **All parameters are "flat"** — no parameter accepts another lambda or builder

<details><summary>Reasoning</summary>

Named arguments eliminate positional ambiguity for optional parameters and make call sites self-documenting. The 2–6 range is derived from readability: at 4+ arguments, callers begin making argument-ordering mistakes; at 7+, the call site becomes a wall of values requiring the reader to count positions. The "flat" constraint is critical — once a parameter itself accepts a lambda, trailing-lambda syntax is no longer available at the call site and a DSL builder (§4) provides better readability. The `@Suppress("LongParameterList")` signal is mechanical evidence that the Kotlin IDE inspection has already fired, confirming the list has exceeded the readable threshold.

</details>

### Measurable threshold: the `@Suppress("LongParameterList")` smell

When you add `@Suppress("LongParameterList")`, that is a signal to consider a builder.
If you add it and any parameter itself takes a lambda, switch to a DSL builder immediately.

<details><summary>Reasoning</summary>

The `LongParameterList` inspection fires at 6+ parameters by default in IntelliJ/Kotlin inspections. Suppressing it with an annotation is an explicit acknowledgement that the code has exceeded readable limits. Using that suppression as a migration trigger prevents gradual drift toward unreadable constructors. The "any parameter takes a lambda" addendum is stricter: once a parameter itself is a lambda, trailing-lambda syntax is unavailable for any other trailing lambda, making the call site structurally unreadable. A DSL builder resolves this by making every property a named `var` assignment.

</details>

### Representative examples

```kotlin
// ✅ Named arguments — flat inputs with a few optional settings
fun registerCommand(
    name: String,
    description: String? = null,
    aliases: List<String> = emptyList(),
    enabled: Boolean = true,
)

// ✅ Boolean flag with default — tiny optional customization
fun refresh(notifyListeners: Boolean = false)

// ✅ Configuration class with defaults — all flat, no nesting
data class RetryOptions(
    val maxAttempts: Int = 3,
    val backoff: Duration = 250.milliseconds,
)
```

---

## 5. Dual Overloads: Value + Lambda

When a property accepts a structured value (e.g., `JsonObject`) that callers may want
to build inline, provide **both** overloads:

```kotlin
// Accept a pre-built value (interop, testing, stored references)
fun arguments(arguments: JsonObject)

// Accept a builder lambda (inline construction in DSL context)
fun arguments(block: JsonObjectBuilder.() -> Unit): Unit = arguments(buildJsonObject(block))
```

**Rule:** Provide the lambda overload only when the type itself has a well-known builder
(e.g., `buildJsonObject`, `buildList`). Do not invent builders just to add a lambda overload.

<details><summary>Reasoning</summary>

kotlinx.serialization's `JsonObjectBuilder` (`formats/json/commonMain/src/…/JsonElementBuilders.kt`) demonstrates both overloads side by side: `fun put(key: String, element: JsonElement)` accepts a pre-built value, while the extension `fun JsonObjectBuilder.putJsonObject(key: String, builderAction: JsonObjectBuilder.() -> Unit)` delegates to `put(key, buildJsonObject(builderAction))` for inline construction. The value overload is essential for testing (injecting fixtures), interop (receiving a `JsonObject` from another layer), and stored references. The lambda overload reduces boilerplate at inline DSL call sites. Providing only the lambda forces `buildJsonObject { }` even when the object already exists; providing only the value forces manual `buildJsonObject { }` at every DSL site. The "well-known builder" constraint prevents circular invention — a lambda overload is only justified when the builder already exists for other reasons.

</details>

---

## 6. Lambda Flavours

Not all lambdas with receivers serve the same purpose. Choose based on intent:

### 6.1 Configuration lambda — `T.() -> Unit`

**Use when:** The object already exists; the lambda mutates or registers things on it.
The lambda is invoked once during construction or registration and does not return a value.

<details><summary>Reasoning</summary>

The `T.() -> Unit` receiver pattern is the foundation of `buildString` (`stdlib/src/kotlin/text/StringBuilder.kt`): `StringBuilder` already exists inside the function and the lambda configures it. The caller never sees the mutable builder — only the immutable `String` result. The same applies to `apply { }` in the stdlib and all Ktor plugin installers (`install(WebSockets) { … }`). The constraint "invoked once" is enforced by `contract { callsInPlace(block, EXACTLY_ONCE) }`, which lets the compiler prove the block runs — enabling `val` definite assignment inside it.

</details>

```kotlin
// ✅ Kotlin stdlib — StringBuilder already exists, lambda configures it
val s = buildString {
    append("Hello, ")
    appendLine("World!")
}

// ✅ Ktor — HttpClient exists, lambda installs plugins and sets defaults
val client = HttpClient(CIO) {
    install(ContentNegotiation) { json() }
    defaultRequest { bearerAuth(token) }
}

// ✅ MCP SDK — Server exists, lambda registers tools/resources on `this`
val server = Server(info, options) {
    addTool("greet", "Say hello") { _ -> ... }
    addResource("file://data", "Data", "...") { _ -> ... }
}
```

### 6.2 Factory lambda — `() -> T`

**Use when:** A new instance must be created per invocation (e.g., one Server per HTTP connection).
There is no shared state; the block is a pure factory.

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

The `() -> T` shape is the minimal factory signature: no receiver, no input, a new value out. Ktor uses it for `webSocket(handler: suspend DefaultWebSocketServerSession.() -> Unit)` — each incoming WebSocket connection invokes the handler independently. The key distinction from `T.() -> Unit` is that the object does not exist yet when the route is registered; the block is *stored* and called later on each connection. Using a configuration lambda (`Server.() -> Unit`) would be wrong here because there is no `Server` instance to configure at registration time.

</details>

```kotlin
// ✅ Ktor — each WebSocket connection gets a fresh handler
fun Route.webSocket(path: String, handler: suspend DefaultWebSocketServerSession.() -> Unit)

// ✅ MCP SDK — each WebSocket connection gets a fresh Server
fun Route.mcpWebSocket(block: () -> Server)
fun Application.mcpWebSocket(block: () -> Server)

// Usage
routing {
    mcpWebSocket { configureServer() }   // called once per connection
}
```

**Why not `Server.() -> Unit` here?** Because the server doesn't exist yet when the route
is registered. The block is stored and called each time a connection arrives.

### 6.3 Factory lambda with context — `Ctx.() -> T`

**Use when:** A new object must be created _and_ the factory needs read-only access to
a framework-provided context (e.g., request headers, session data).
The receiver is for _reading_, not mutating.

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

This flavour combines `() -> T` (creates a new object) with a receiver (provides context). The receiver is the framework's session/request object — not something you configure, but something you read from (`call.request.header("Authorization")`). If the receiver were mutable, it would blur the boundary between "reading context" and "mutating the session", which is a correctness hazard. The `Ctx.() -> T` signature makes the intent unambiguous: `Ctx` is read-only input, `T` is the produced output.

</details>

```kotlin
// ✅ SSE — lambda receives the session to inspect headers, returns a new Server
fun Route.mcp(path: String, block: ServerSSESession.() -> Server)

// Usage — receiver used to read auth headers, not configure the session
routing {
    mcp("/sse") {
        val token = call.request.header("Authorization")
        configureServer(token)
    }
}
```

### 6.4 Action lambda — `(A, B, ...) -> R`

**Use when:** The lambda is not a configuration block but a computation called by the API.
It performs work on data provided by the caller and reports a result back.

```kotlin
// ✅ kotlinx-io: readFromHead delegates reading to the caller's lambda,
//    which returns the number of bytes it consumed
inline fun readFromHead(
    buffer: Buffer,
    readAction: (bytes: ByteArray, startIndexInclusive: Int, endIndexExclusive: Int) -> Int
): Int {
    contract { callsInPlace(readAction, EXACTLY_ONCE) }
    ...
}
```

Action lambdas are always `inline` with `callsInPlace(EXACTLY_ONCE)` when called exactly once.
They look like callbacks but they run synchronously and return a meaningful value.

<details><summary>Reasoning</summary>

kotlinx-io's `UnsafeBufferOperations.readFromHead` (`core/common/src/unsafe/UnsafeBufferOperations.kt`) is the canonical example: the library provides a `ByteArray` slice to the caller's lambda, which returns the number of bytes consumed. The lambda is `inline` and `callsInPlace(EXACTLY_ONCE)` — this means: (1) no lambda object is allocated (critical for hot I/O paths); (2) the compiler knows the lambda runs exactly once, so `val` variables assigned inside it are definitively initialised afterward. Without `inline`, a closure allocation would occur on every call — unacceptable for a zero-copy buffer API. The `(A, B) -> R` shape (no receiver, explicit inputs, meaningful return) signals to readers that this is a computation, not a configuration block.

</details>

### 6.5 Coroutine builder lambda — `suspend CoroutineScope.() -> T`

**Use when:** The lambda is a coroutine body that executes within a structured concurrency
scope. The receiver is the `CoroutineScope` so that child coroutines can be launched
and will be bounded by the parent scope's lifetime.

```kotlin
// ✅ kotlinx.coroutines — launch/async take a suspend lambda with CoroutineScope receiver
fun CoroutineScope.launch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit  // ← coroutine builder lambda
): Job

// ✅ coroutineScope / supervisorScope — create a new scope, wait for all children
suspend fun <R> coroutineScope(block: suspend CoroutineScope.() -> R): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    ...
}
```

**Key properties:**
- Always `suspend` — can only be called from a coroutine or another suspend function
- Receiver is `CoroutineScope` — child coroutines launched inside are bounded by this scope
- Annotated with `contract { callsInPlace(block, EXACTLY_ONCE) }` when the block runs exactly once
- Distinct from `() -> T` (which is not `suspend` and does not provide a coroutine scope)

<details><summary>Reasoning</summary>

`kotlinx.coroutines`' `launch` and `async` (`Builders.common.kt`) take `suspend CoroutineScope.() -> Unit/T`. The `CoroutineScope` receiver is what makes structured concurrency work: any coroutine launched inside the block with `launch { }` or `async { }` becomes a child of the outer scope's `Job`, so cancellation propagates automatically and the parent waits for all children before completing. Without the `CoroutineScope` receiver, child coroutines would have no parent job and would escape the structured hierarchy. `coroutineScope { }` and `supervisorScope { }` use `contract { callsInPlace(block, EXACTLY_ONCE) }` so the compiler can perform definite assignment analysis across the suspension boundary.

</details>

| Flavour | Receiver purpose | Suspend? | Returns |
|---|---|---|---|
| `T.() -> Unit` | Mutate / register on existing `T` | No | `Unit` |
| `() -> T` | Create a new `T` from scratch | No | new `T` |
| `Ctx.() -> T` | Read context `Ctx`, create a new `T` | No | new `T` |
| `(A, B) -> R` | Compute a result using inputs from the API | No | computed value |
| `suspend CoroutineScope.() -> T` | Run coroutine body in a bounded scope | Yes | `T` |

---

## 10. Constructor vs. Builder vs. Plain Function

| Scenario | Recommended form |
|---|---|
| Data class with all-required fields | Primary constructor |
| Data class with many optional fields | Named parameters + defaults |
| 5+ optional options forming one concept | `Configuration` data class |
| Protocol/message object built frequently | `build*` DSL builder |
| Heavyweight singleton (format, service) | Type-named factory `fun Type(block)` |
| Derivative of existing instance | Copy builder `fun Type(from = Default, block)` |
| Framework integration | Extension function on framework type |
| One-shot factory for internal use | Plain `fun create*(...)` |
| Common result shapes | Companion object factory |

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

This table consolidates all the individual rules from §1–§9 into a single decision matrix. The choices are not arbitrary — each row corresponds to a principle established in earlier sections and demonstrated by canonical library examples. The primary constructor is the simplest form (data class with all-required fields); each step down the table adds complexity only when the simpler form cannot express the design. Choosing a more complex form when a simpler one suffices is over-engineering; choosing a simpler form when complexity is warranted produces unreadable call sites.

</details>

```kotlin
// Companion factory — for common result shapes, not full construction
fun CallToolResult.Companion.success(content: String, meta: JsonObject? = null): CallToolResult
fun CallToolResult.Companion.error(content: String, meta: JsonObject? = null): CallToolResult
```

---

## Summary: The Numbers and the Questions

**Parameter-count thresholds:**

- **≤ 3 required → plain parameters**
- **4–6 with defaults, all flat → named arguments**
- **Config stored on the object and exposed for diagnostics → immutable `Configuration` object** (regardless of count — see §7)
- **≥ 3 with optionals or any nesting → DSL builder**

**Lambda selection questions:**

1. Does the lambda configure an existing object? → `T.() -> Unit`
2. Does the lambda create a new object per call? → `() -> T`
3. Does it need read-only access to a framework context to create a new object? → `Ctx.() -> T`
4. Is it an optional extension point on a framework call? → `T.() -> Unit = {}`
5. Is it a coroutine body that must be bounded by a structured scope? → `suspend CoroutineScope.() -> T`
6. Is it a synchronous computation on API-provided data? → `(A, B) -> R` (inline + `callsInPlace`)
7. Should the lambda's receiver restrict which suspend functions can be called? → `@RestrictsSuspension`

**Framework integration:**

- Use extension functions on framework types, not standalone wrappers
- Annotate with the framework's own DSL marker (`@KtorDsl`)
- Expose an escape-hatch lambda as the last parameter for underlying request customisation

**Coroutines and shared state:**

- Factory functions named after types need `@Suppress("FunctionName")`
- Hot data streams: expose read-only interface (`StateFlow`, `SharedFlow`), back with private mutable (`_backing`)
- Dangerous global state (e.g., `GlobalScope`) must carry `@DelicateCoroutinesApi`
- Use `@ExperimentalForInheritanceFooApi` when an interface is stable to _use_ but not to _extend_

These numbers are not arbitrary: at 3 required parameters, positional calls remain readable.
At 4+, callers start making argument-ordering errors.
At 5+ optional parameters, a `Configuration` class is easier to pass between layers than a long
function signature. At 3+ fields with optionals, a builder's `var` assignments outperform named
arguments in readability because each line is self-labelled and IDE autocomplete guides construction.
