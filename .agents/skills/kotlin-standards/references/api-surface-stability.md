# Kotlin API Surface and Stability

Use this reference for public-surface design, visibility boundaries, opt-in tiers, and mutable versus read-only exposure patterns.

## Included sections

- 14. Visibility Rules
- 15. API Stability Tiers (`@OptIn` Annotations)
- 19. Mutable/Read-Only Pairing

## 14. Visibility Rules

| Element | Visibility |
|---|---|
| Top-level `build*` function | `public` |
| Builder class | `public` |
| Builder constructor | `@PublishedApi internal` (prevents direct instantiation, allows `inline`) |
| `build()` method | `@PublishedApi internal` (called only by `inline` entry point) |
| Builder helper methods | `public` (they form the DSL surface) |
| Intermediate state fields | `private` or `protected` |

Using `@PublishedApi internal` on the constructor and `build()` method is the correct
pattern: it hides them from normal usage while allowing the `inline` top-level function
to call them after inlining.

<details><summary>Reasoning</summary>

The stdlib's `HexFormat.Builder` (`stdlib/src/kotlin/text/HexFormat.kt`) is `public class Builder @PublishedApi internal constructor()` — the class is public so it can appear in the DSL surface and type signatures, but the constructor is `@PublishedApi internal` so only the inline `HexFormat { }` entry point can create it. `build()` is `@PublishedApi internal` for the same reason: it is called inside the inline factory after inlining, but must not be callable from external code. Making `build()` `public` would allow callers to invoke it on a partially-configured builder, bypassing the validation in the `buildFoo { }` entry function.

</details>

---

## 15. API Stability Tiers (`@OptIn` Annotations)

Both `kotlinx-io` and `kotlinx.serialization` use **multiple distinct opt-in annotations**
to communicate different risk levels. Use the same multi-tier approach in your own API.

### Multi-tier model

kotlinx-io uses three distinct annotations. kotlinx.serialization adds a fourth.
Choose the ones that fit your API's risk profile:

| Annotation | `RequiresOptIn` level | Who should opt in | When to use |
|---|---|---|---|
| `@InternalFooApi` | `ERROR` | No one — internal only | Implementation details not for public use |
| `@DelicateFooApi` | `WARNING` | Expert users only | Correct but easy to misuse |
| `@ExperimentalFooApi` | `WARNING` | Early adopters | Stable in shape, may change |
| `@UnsafeFooApi` | `WARNING` | Experts with documented care | Causes data corruption if misused |

<details><summary>Reasoning</summary>

kotlinx-io's `Annotations.kt` (`core/common/src/Annotations.kt`) defines all three tiers with explicit, distinct messages. `@InternalIoApi` is `ERROR` because its KDoc says "subject to change or removal and is not intended for use outside the library." `@DelicateIoApi` is `WARNING` because the API is "correct but careful use required." `@UnsafeIoApi` is `WARNING` because it "may cause data corruption or loss." kotlinx.coroutines' `Annotations.kt` follows the same `ERROR` for `@InternalCoroutinesApi`, `WARNING` for `@DelicateCoroutinesApi` and `@ExperimentalCoroutinesApi`. Using a single `@Experimental` annotation for all risk levels loses information — a caller opting in to an experimental API has no idea whether they risk data corruption or merely an API rename.

</details>

```kotlin
// kotlinx-io model — three distinct warnings
@RequiresOptIn(level = ERROR)   annotation class InternalIoApi
@RequiresOptIn(level = WARNING) annotation class DelicateIoApi   // correct but careful use required
@RequiresOptIn(level = WARNING) annotation class UnsafeIoApi     // data corruption risk

// kotlinx.serialization model
@RequiresOptIn(level = WARNING) annotation class ExperimentalSerializationApi
@RequiresOptIn(level = ERROR)   annotation class InternalSerializationApi
@RequiresOptIn(level = ERROR)   annotation class SealedSerializationApi  // don't inherit
```

### `sealed interface` / `sealed class` as "use but don't implement"

`kotlinx-io`'s `Source` and `Sink` are `sealed interface`. This means:

- Callers can use, pass, and store instances freely
- Callers cannot implement the interface without opting in
- New methods can be added in future versions without breaking existing implementations

```kotlin
// ✅ kotlinx-io pattern — sealed prevents uncontrolled implementations
public sealed interface Source : RawSource {
    @InternalIoApi
    val buffer: Buffer             // internal details hidden behind opt-in
    fun exhausted(): Boolean
    fun readByte(): Byte
    // ...
}
```

Use `sealed interface` or `sealed class` for public API types when:
- The type is used by callers but should not be extended by callers
- You need freedom to add new methods in future versions
- Implementations are fully under your control

<details><summary>Reasoning</summary>

kotlinx-io's `Source` (`core/common/src/Source.kt`) is `public sealed interface Source : RawSource`. Its KDoc states: "Thread-safety guarantees — until stated otherwise, `Source` implementations are not thread safe." The `sealed` modifier means external code can call `Source` methods freely but cannot implement the interface — if a new `suspend fun peek()` method is added in a future version, no external implementation breaks because there are none. Without `sealed`, adding any new abstract method to a `Source` interface would be a binary-breaking change for all callers who implemented it.

</details>

### Inheritance-specific opt-in tiers

kotlinx.coroutines adds two more opt-in annotations specifically for the "safe to _use_,
unsafe to _inherit_ from" case:

```kotlin
// ✅ kotlinx.coroutines — WARNING: new methods may be added in future, breaking inheritance
@Target(AnnotationTarget.CLASS)
@RequiresOptIn(level = WARNING, message = "...")
annotation class ExperimentalForInheritanceCoroutinesApi

// ✅ for types with predefined instances handled specially by the library
@Target(AnnotationTarget.CLASS)
@RequiresOptIn(level = WARNING, message = "...")
annotation class InternalForInheritanceCoroutinesApi
```

Use these (or your own equivalents) when:
- The interface is stable for _calling_ but not for _implementing_
- You need the freedom to add new abstract/open methods in future versions
- The `Flow` interface documents this informally in KDoc ("not stable for inheritance")
  but these annotations enforce it at the compiler level

<details><summary>Reasoning</summary>

kotlinx.coroutines' `Annotations.kt` defines `@ExperimentalForInheritanceCoroutinesApi` with the message: "Either new methods may be added in the future, which would break the inheritance, or correctly inheriting from it requires fulfilling contracts that may change in the future." `@InternalForInheritanceCoroutinesApi` adds: "the library may handle predefined instances of this in a special manner." `MutableStateFlow` carries this annotation — the interface has predefined internal implementations that the coroutines library dispatches on specially. External implementations would not benefit from these optimisations and would silently produce incorrect behaviour at dispatch boundaries.

</details>

**Updated 5-tier model:**

| Annotation | Level | Who opts in | Purpose |
|---|---|---|---|
| `@InternalFooApi` | `ERROR` | No one | Implementation details |
| `@DelicateFooApi` | `WARNING` | Expert users | Correct but easy to misuse |
| `@ExperimentalFooApi` | `WARNING` | Early adopters | May change shape |
| `@UnsafeFooApi` | `WARNING` | Experts with documented care | Data corruption risk |
| `@ExperimentalForInheritanceFooApi` | `WARNING` | Library extenders only | Adding methods in future |

### Helpful `@Deprecated(level = ERROR)` overloads

When users commonly try to call a function in a context where it cannot work correctly
(e.g., calling `launch` outside a `CoroutineScope`), add a `@Deprecated(level = ERROR)`
overload with a descriptive message pointing to the correct approach. This turns an opaque
"unresolved reference" compile error into a diagnostic message with a clear migration path:

```kotlin
// ✅ kotlinx.coroutines Guidance.kt — impossible usage becomes a compile error
@Deprecated(
    "'launch' can not be called without the corresponding coroutine scope. " +
    "Consider wrapping 'launch' in 'coroutineScope { }', using 'runBlocking { }', ...",
    level = DeprecationLevel.ERROR
)
@kotlin.internal.LowPriorityInOverloadResolution
public fun launch(context: CoroutineContext = ..., block: suspend CoroutineScope.() -> Unit): Job {
    throw UnsupportedOperationException("Should never be called")
}
```

The `@LowPriorityInOverloadResolution` ensures this overload only matches when no valid
overload is applicable. Callers using the correct `CoroutineScope.launch` see nothing.

<details><summary>Reasoning</summary>

`Guidance.kt` in kotlinx.coroutines defines a top-level `fun launch(…)` annotated with `@Deprecated(level = ERROR)` and `@LowPriorityInOverloadResolution`. Without it, a caller who writes `launch { }` outside a `CoroutineScope` would get an "unresolved reference" compile error — confusing for beginners who do not know they need a scope. With the guidance overload, the error message becomes "'launch' can not be called without the corresponding coroutine scope. Consider wrapping 'launch' in 'coroutineScope { }'…" — a directly actionable diagnostic. `@LowPriorityInOverloadResolution` ensures the guidance overload loses to the real `CoroutineScope.launch` when a scope is in context, so it is completely invisible to correct usage.

</details>

### Grouping unsafe operations into a singleton `object`

When a group of functions is dangerous but necessary, put them in a named `object`
rather than top-level functions. The call site (`UnsafeBufferOperations.readFromHead(...)`)
makes the danger explicit at every use point.

```kotlin
// ✅ kotlinx-io — singleton object draws attention at every call site
@UnsafeIoApi
object UnsafeBufferOperations {
    inline fun readFromHead(buffer: Buffer, readAction: (ByteArray, Int, Int) -> Int): Int
    inline fun writeToTail(buffer: Buffer, minimumCapacity: Int, writeAction: (ByteArray, Int, Int) -> Int): Int
}

// Call site always names the object — no way to "accidentally" call it
UnsafeBufferOperations.readFromHead(buf) { bytes, start, end -> ... }
```

<details><summary>Reasoning</summary>

`UnsafeBufferOperations` in kotlinx-io (`core/common/src/unsafe/UnsafeBufferOperations.kt`) is annotated `@UnsafeIoApi` and declared as `object`. Its KDoc warns: "Attempts to write data into [bytes] array once it was moved may lead to data corruption." Placing dangerous operations in a named `object` makes the qualified call site `UnsafeBufferOperations.readFromHead(…)` a visual red flag at every use point. A top-level `readFromHead(…)` would look identical to any other buffer utility at the call site — the danger is invisible. The `object` wrapper adds zero runtime overhead while making the risk explicitly visible in every diff and code review.

</details>

---

## 19. Mutable/Read-Only Pairing

When a data-holder type has both read-only consumers and a single mutable producer,
separate the concerns into two interfaces and back the public property with a private
mutable instance:

```kotlin
// ✅ kotlinx.coroutines pattern — StateFlow / MutableStateFlow
class CounterModel {
    private val _counter = MutableStateFlow(0)  // private mutable
    val counter: StateFlow<Int> = _counter.asStateFlow()  // public read-only — structural wrapper, not type upcast

    fun inc() {
        _counter.update { it + 1 }
    }
}

// ✅ SharedFlow variant
class EventBus {
    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    suspend fun publish(event: Event) = _events.emit(event)
}
```

### Rules for mutable/read-only pairing

1. **Name the backing field** with a leading underscore: `_counter`, `_events`.
2. **Expose the public property** with the read-only type and no underscore: `counter`, `events`.
3. **Use `.asXxx()` conversion** (`asStateFlow()`, `asSharedFlow()`) rather than relying on
   type upcasting, so the exposed type is structurally read-only even if upcast-assignable.
4. **Put the mutable interface** in `MutableXxx` naming: `MutableStateFlow`, `MutableSharedFlow`.
   The read-only base interface has no prefix: `StateFlow`, `SharedFlow`.
5. **Never expose the mutable type** as a public property or return value;
   it must remain an implementation detail.

<details><summary>Reasoning</summary>

`StateFlow.kt` in kotlinx.coroutines (`kotlinx-coroutines-core/common/src/flow/StateFlow.kt`) is the canonical reference. Its KDoc uses exactly the `_counter`/`counter` pattern: `private val _counter = MutableStateFlow(0)` and `val counter = _counter.asStateFlow()`. Rule 3 (`.asStateFlow()` over upcast) is stated in the source: `asStateFlow()` returns a `ReadonlyStateFlow` wrapper — if you upcast `val counter: StateFlow<Int> = _counter`, a caller can downcast back to `MutableStateFlow` and mutate it. `asStateFlow()` prevents this. Rule 5 is structural: `MutableStateFlow` is documented as "not stable for inheritance" (`@InternalForInheritanceCoroutinesApi`), confirming it has special internal dispatch — exposing it publicly would allow callers to depend on that special behaviour, which is fragile.

</details>

### When to apply this pattern

| Scenario | Apply? |
|---|---|
| A hot data stream read by multiple consumers | Yes |
| A UI state model updated by the ViewModel | Yes |
| An internal buffer accessed only within one class | No — keep as `var` |
| A configuration object set once at startup | No — use immutable `val` |

<details><summary>Reasoning</summary>

**Why: The reason is unknown**

The pattern adds two objects (`MutableStateFlow` + its `ReadonlyStateFlow` wrapper) and a layer of indirection. That cost is justified only when multiple consumers observe the same hot stream. For a configuration set once at startup, an immutable `val` is cheaper and clearer — there is no mutation to encapsulate and no observer to protect. For an internal buffer accessed within one class, the mutation is already private — the pattern provides no additional encapsulation and only adds ceremony.

</details>

---
