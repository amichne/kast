# Kotlin API Extensions and Factories

Use this reference when shaping extension-based APIs, framework integrations, factory naming, or reified convenience overloads.

## Included sections

- 8. Extension Functions on Framework Types
- 9. Naming Factory Functions
- 16. `reified` Inline Shortcuts

## 8. Extension Functions on Framework Types

When integrating with an existing framework (Ktor, coroutines, etc.), prefer extension functions
over companion objects, standalone functions, or wrapper classes.

### When to write an extension function

- You need to operate _within_ a framework scope (`Route`, `Application`, `HttpClient`)
- The framework type provides mandatory runtime context (routing tree, HTTP engine, etc.)
- Installation of framework plugins is part of the operation

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

Extension functions on framework types encode the dependency on the framework at the type level: the function cannot be called unless the caller already has a `Route`, `Application`, or `HttpClient` in scope. This prevents the API from being misused outside the correct lifecycle. A standalone `fun mcpSseTransport(client: HttpClient, …)` would work, but it does not prevent being called with an unconfigured client or outside a Ktor application context. Extension functions also participate in the framework's DSL scope resolution, enabling `@KtorDsl` to prevent calls outside the routing DSL.

</details>

```kotlin
// ✅ HttpClient extensions — client is required infrastructure, not optional
fun HttpClient.mcpSseTransport(urlString: String? = null, ...): SseClientTransport
fun HttpClient.mcpStreamableHttpTransport(urlString: String, ...): StreamableHttpClientTransport
suspend fun HttpClient.mcpSse(urlString: String? = null, ...): Client      // factory shortcut
suspend fun HttpClient.mcpStreamableHttp(urlString: String, ...): Client

// ✅ Ktor server routing extensions
fun Route.mcp(path: String, block: ServerSSESession.() -> Server)
fun Application.mcp(block: ServerSSESession.() -> Server)         // installs SSE automatically
fun Application.mcpStreamableHttp(path: String, ..., block: RoutingContext.() -> Server)
```

### `@KtorDsl` (and framework-specific DSL markers)

When writing extension functions that are part of a framework's DSL, annotate them with the
framework's own DSL marker, not just your own:

```kotlin
@KtorDsl                               // Ktor's marker — prevents use outside Ktor DSL context
public fun Route.mcp(path: String, block: ServerSSESession.() -> Server)
```

Use `@KtorDsl` (or equivalent) when the function is _only_ meaningful inside a specific framework
DSL block. This prevents callers from accidentally calling it at the top level.

<details><summary>Reasoning</summary>

`@KtorDsl` is Ktor's own `@DslMarker` annotation. When applied to an extension function on `Route` or `Application`, the Kotlin compiler enforces that it can only be called from within the corresponding Ktor DSL receiver scope. Without it, an IDE provides no warning when `mcp("/sse") { }` is called at the top level of a file or inside an unrelated class — resulting in a runtime error because the routing tree is not being built. The same principle applies to any framework DSL marker: `@HtmlTagMarker`, `@KtorDsl`, `@McpDsl` — they all leverage `@DslMarker` to restrict call sites to the intended scope.

</details>

### The escape-hatch lambda (`requestBuilder: HttpRequestBuilder.() -> Unit = {}`)

When wrapping a framework type, expose an optional lambda that lets callers customise the
underlying framework object without your API enumerating every option:

```kotlin
// ✅ Three Ktor transports all follow the same pattern:
fun HttpClient.mcpSseTransport(
    urlString: String? = null,
    reconnectionTime: Duration? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},   // ← escape hatch
): SseClientTransport

fun HttpClient.mcpStreamableHttpTransport(
    url: String,
    reconnectionTime: Duration? = null,
    requestBuilder: HttpRequestBuilder.() -> Unit = {},   // ← escape hatch
): StreamableHttpClientTransport
```

**Rules for the escape-hatch lambda:**

1. Always provide an empty default (`= {}`): it is opt-in, never forced on the caller.
2. Name it consistently (`requestBuilder`, `block`, `configure`) across related APIs in the same module.
3. Its type is the framework's own builder type — do not wrap it in your own abstraction.
4. Place it as the last parameter so callers can use trailing-lambda syntax when they need it.

The escape-hatch avoids the "add a new parameter for every header" treadmill while keeping the
primary parameters clean and explicit.

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

Without an escape hatch, every new HTTP header or request option that a caller needs forces a new parameter on the transport function — an unbounded treadmill. The empty-default rule (`= {}`) keeps the escape hatch completely invisible to callers who do not need it: the function's primary parameters stay clean. Placing it last enables trailing-lambda syntax (`mcpSseTransport(url) { bearerAuth(token) }`), which reads naturally. Using the framework's own builder type (`HttpRequestBuilder`) avoids forcing callers to learn an intermediate abstraction — they already know the Ktor DSL.

</details>

### Where to place extension functions and how to name files

Extension functions in the Kotlin ecosystem follow predictable file-placement conventions. The
driving question is: **whose vocabulary do these extensions belong to?**

#### Co-locate with your own type (same file)

If extensions are few and tightly coupled to a class you own, place them in the same file:

```
io/modelcontextprotocol/kotlin/sdk/client/
    McpClient.kt        // class McpClient + its direct extensions
```

#### Separate file named after the receiver type

When extensions on a third-party type grow beyond a handful, extract them into a dedicated file.
Name it `<ReceiverType>Extensions.kt` (or a descriptive verb noun when a clear theme exists):

```
// stdlib pattern — one file per extended type/concept
Collections.kt          // extensions on Collection, List, Map, …
Strings.kt              // extensions on String, Char, CharSequence
Sequences.kt            // extensions on Sequence<T>

// coroutines pattern — action-oriented when the theme is a behaviour
Builders.common.kt      // launch, async, withContext, …
Delay.kt                // delay, withTimeout, …
Flow.kt                 // Flow interface + core operators
```

Apply the same logic in your own modules:

```
// ✅ Good — theme is the receiver type or a clear action
HttpClientExtensions.kt     // extensions on HttpClient
RouteExtensions.kt          // extensions on Route / Application
FlowExtensions.kt           // Flow operators for your domain

// ❌ Avoid — name reveals nothing about the receiver or intent
Utils.kt
Helpers.kt
Misc.kt
```

#### Package placement rules

| Scenario | Package for the extension file |
|---|---|
| Extension adds to your own public API | Same package as the extended type |
| Extension bridges two libraries you own | Package of the _calling_ library |
| Extension is purely internal | `internal` sub-package, e.g. `…sdk.internal` |

```kotlin
// ✅ Extending HttpClient — file lives in the client module's package
// File: kotlin-sdk-client/…/client/HttpClientExtensions.kt
package io.modelcontextprotocol.kotlin.sdk.client

fun HttpClient.mcpSseTransport(...): SseClientTransport = ...
```

#### `@file:JvmName` on JVM

When a file contains only extension functions (no classes), the compiler generates a class named
`<FileName>Kt` by default. Provide a cleaner JVM name for Java callers:

```kotlin
// File: FlowExtensions.kt
@file:JvmName("McpFlows")  // Java sees McpFlows.collectMessages(flow, ...)

package io.modelcontextprotocol.kotlin.sdk

fun Flow<JsonRpcMessage>.collectMessages(...) { ... }
```

#### Summary checklist

- One concept / receiver type → one file.
- File name = `<ReceiverType>Extensions.kt` or descriptive action noun (`Builders.kt`, `Operators.kt`).
- Place in the package of the module that _owns_ the integration.
- Add `@file:JvmName` when the file contains only top-level functions and Java interop matters.

<details><summary>Reasoning</summary>

The Kotlin stdlib organises extensions by receiver type: `Collections.kt` for `Collection`/`List`/`Map` extensions, `Strings.kt` for `String`/`CharSequence`, `Sequences.kt` for `Sequence<T>`. kotlinx.coroutines uses action-oriented names when the theme is a behaviour: `Builders.common.kt` (with `@file:JvmName("BuildersKt")`) for `launch`/`async`/`withContext`, `Delay.kt` for `delay`/`withTimeout`. The `@file:JvmName` annotation on `Builders.common.kt` gives Java callers a predictable class name (`BuildersKt`) instead of the compiler-generated `Builders_commonKt`. Files named `Utils.kt` or `Helpers.kt` fail this convention — they reveal nothing about the receiver type or theme, making discovery impossible without an IDE search.

</details>

---

## 9. Naming Factory Functions

The name of a factory function signals its relationship to the type it creates.

### `build*` prefix — for protocol/message objects

Use `build*` when the factory creates a **data/message object** from a builder.
The prefix makes it clear the function runs a builder, not a constructor.

<details><summary>Reasoning</summary>

kotlinx.serialization uses `buildJsonObject` and `buildJsonArray` (`formats/json/commonMain/src/…/JsonElementBuilders.kt`) as the entry points to their respective builders. The `build*` prefix signals three things simultaneously: (1) a `Builder` class is involved internally; (2) the result is a freshly constructed value, not a singleton; (3) the function is the primary intended call site — callers should not instantiate `JsonObjectBuilder` directly. This distinguishes `buildJsonObject { }` (one-shot construction) from `Json { }` (heavyweight singleton), which would be confusing if both used the same `build*` prefix.

</details>

```kotlin
// ✅ Protocol message types — callers build many of these
fun buildCallToolRequest(block: CallToolRequestBuilder.() -> Unit): CallToolRequest
fun buildCreateMessageRequest(block: CreateMessageRequestBuilder.() -> Unit): CreateMessageRequest
```

### Type-named factory — for format/service singletons

When the result is a **heavyweight singleton** (a format, a client, a service) with
a large, optional configuration surface, name the factory after the type itself.
This reads as a pseudo-constructor.

```kotlin
// ✅ kotlinx.serialization — Json is a sealed class, `Json { }` is its factory
fun Json(from: Json = Json.Default, builderAction: JsonBuilder.() -> Unit): Json

// Callers read naturally:
val json = Json { ignoreUnknownKeys = true }
val debug = Json(json) { prettyPrint = true }  // ← copy builder with "from"
```

**Rules:**
- The type must be `sealed` or `abstract` so callers cannot instantiate it directly.
- The companion can serve as the default instance (`Json.Default`).
- Add an optional `from: T = T.Default` first parameter for the **copy builder** pattern.

<details><summary>Reasoning</summary>

`Json` in kotlinx.serialization (`formats/json/commonMain/src/…/Json.kt`) is a `sealed class` with a `fun Json(from: Json = Json.Default, builderAction: JsonBuilder.() -> Unit): Json` factory. The `sealed` constraint is load-bearing: it prevents external subclassing, which would break the library's internal dispatch. The type-named factory reads as a pseudo-constructor — `val json = Json { ignoreUnknownKeys = true }` is idiomatic and immediately legible even to newcomers. `CoroutineScope(context)` and `MainScope()` in kotlinx.coroutines follow the same pattern: factory functions named after the interface they return, with `@Suppress("FunctionName")` to silence the lint warning.

</details>

### Copy builder pattern — `fun Type(from: Type = Type.Default, block: TypeBuilder.() -> Unit)`

When an existing instance should be the baseline for a new one with overrides:

```kotlin
// ✅ kotlinx.serialization copy builder
val defaultJson = Json { encodeDefaults = true }
val debugJson = Json(defaultJson) { prettyPrint = true }   // inherits encodeDefaults

// In MCP SDK — extend existing config without repeating every field
val strictJson = Json(McpJson) { explicitNulls = true }
```

The `from` parameter seeds all builder properties from the given instance, so only
the changed fields need to be specified. This is more maintainable than re-specifying
everything when one option changes.

<details><summary>Reasoning</summary>

`Json.kt` documents this pattern explicitly in its KDoc: "Json format configuration can be refined using the corresponding constructor: `val debugEndpointJson = Json(defaultJson) { prettyPrint = true }` — will inherit the properties of defaultJson." `JsonBuilder` is initialised from the given `Json` instance: `var encodeDefaults = json.configuration.encodeDefaults`, and so on for all 17 properties. Without the copy builder, every derived configuration must repeat every field — a maintenance hazard when the base configuration changes. The `from = Default` parameter makes the pattern opt-in: callers who do not need inheritance simply omit it.

</details>

### `@Suppress("FunctionName")` for type-named factories

When a factory function is named after the type it creates (pseudo-constructor style),
Kotlin's lint raises a `FunctionName` warning because the name starts with an uppercase letter.
Suppress it explicitly so the intent is clear:

```kotlin
// ✅ kotlinx.coroutines — factory function named after the interface
@Suppress("FunctionName")
public fun CoroutineScope(context: CoroutineContext): CoroutineScope = ContextScope(...)

@Suppress("FunctionName")
public fun MainScope(): CoroutineScope = ContextScope(SupervisorJob() + Dispatchers.Main)

@Suppress("FunctionName")
public fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = StateFlowImpl(value)
```

**Rule:** Every top-level factory function whose name starts with an uppercase letter
**must** have `@Suppress("FunctionName")`. Without it, Kotlin's IDE and linters report
a spurious warning that signals incorrect style.

<details><summary>Reasoning</summary>

`CoroutineScope.kt` in kotlinx.coroutines has `@Suppress("FunctionName")` on `fun MainScope()` and `fun CoroutineScope(context)` at lines 121 and 297 respectively. `StateFlow.kt` has it on `fun MutableStateFlow(value)`. The Kotlin `FunctionName` inspection requires function names to start with a lowercase letter — an intentional convention for regular functions. Type-named factories deliberately violate this convention to read as pseudo-constructors. `@Suppress("FunctionName")` is the correct signal that the uppercase name is intentional, not a mistake. Without it, CI lint tools and IDE inspections produce false positives at every occurrence, training reviewers to ignore real issues.

</details>

### Immutable configuration object vs mutable `Builder`

For heavyweight configured types, split into two classes:

| Class | Constructor | Mutability | Purpose |
|---|---|---|---|
| `FooBuilder` | `internal` | mutable `var` | Used during configuration block |
| `FooConfiguration` | `internal` | immutable `val` | Stored on, and exposed by, the created instance |

```kotlin
// ✅ Pattern from kotlinx.serialization
class JsonBuilder internal constructor(json: Json) {
    var encodeDefaults: Boolean = json.configuration.encodeDefaults
    // ...
    internal fun build(): JsonConfiguration = JsonConfiguration(encodeDefaults, ...)
}

sealed class Json(val configuration: JsonConfiguration, ...) {
    // configuration is read-only and immutable — callers cannot mutate it
}
```

This means `configuration` on the live instance is always stable and immutable.
It can safely be shared across threads and used for diagnostics.

<details><summary>Reasoning</summary>

`JsonConfiguration` has `internal constructor` — external code cannot create or modify it. `JsonBuilder` has `var` properties that mirror it, and `internal fun build(): JsonConfiguration` that produces the immutable configuration object. `Json` stores the result as `val configuration: JsonConfiguration`. This split is the reason custom serializers can safely read `json.configuration.encodeDefaults` from any thread without synchronization — the `val` fields of `JsonConfiguration` are final on the JVM. If configuration were stored as a mutable `JsonBuilder`, it could be modified after construction, breaking thread-safety and diagnostics.

</details>

---

## 16. `reified` Inline Shortcuts

When an API requires an explicit serializer or type token, always add an `inline reified`
overload that infers the type from the call site:

```kotlin
// ✅ kotlinx.serialization — explicit serializer (required for interop and custom serializers)
fun <T> StringFormat.encodeToString(serializer: SerializationStrategy<T>, value: T): String

// ✅ Reified shortcut — callers use this 95% of the time
inline fun <reified T> StringFormat.encodeToString(value: T): String =
    encodeToString(serializersModule.serializer(), value)
```

**Rule:** Add the reified overload as an extension function, not a member, so it doesn't
pollute the core interface. Always keep the explicit-serializer version — it is needed
for interop, reflection-free environments, and custom serializers.

<details><summary>Reasoning</summary>

`core/commonMain/src/kotlinx/serialization/SerialFormat.kt` shows both overloads side by side: `fun <T> StringFormat.encodeToString(serializer: SerializationStrategy<T>, value: T): String` is the member (interface method), and `inline fun <reified T> StringFormat.encodeToString(value: T): String = encodeToString(serializersModule.serializer(), value)` is the extension. The extension delegates to `serializersModule.serializer<T>()` which uses reflection — unavailable in some multiplatform targets and incompatible with custom serializers. Keeping the explicit-serializer overload is therefore not optional: it is the only correct path in reflection-free environments (e.g., native with IR). The reified overload is purely a convenience shortcut for the 95% case.

</details>

---
