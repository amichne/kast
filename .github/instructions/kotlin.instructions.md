---
applyTo: "**/kotlin/**/*.kt"
---

# Kotlin Type-Driven Design Standard

## Scope

This standard applies to all generated code. The principles are language-agnostic.
Kotlin is the primary exemplar language; when generating code in other languages,
apply the equivalent idioms (e.g., Rust's newtype pattern, TypeScript's branded types,
Java's sealed classes).

---

## Principle 1: Make illegal states unrepresentable

Enumerate all legal variants of a concept using the type system. Every consumer
must handle all variants — the compiler, not convention, enforces completeness.

Never expose a raw primitive (`String`, `Int`, `Map<String, String>`) where a
named type with constrained construction would prevent misuse.

```kotlin
// GOOD — sealed hierarchy; exhaustive when is compiler-checked
sealed interface PaymentMethod {
data class Card(val last4: CardLast4, val expiry: Expiry) : PaymentMethod
data class BankTransfer(val iban: Iban) : PaymentMethod
data object Cash : PaymentMethod
}

fun describe(method: PaymentMethod): String = when (method) {
is PaymentMethod.Card         -> "Card ending ${method.last4}"
is PaymentMethod.BankTransfer -> "Bank transfer to ${method.iban}"
PaymentMethod.Cash            -> "Cash"
// Adding a new variant here causes a compile error until handled
}

// BAD — string-based dispatch; adding a variant silently falls through
fun describe(type: String, details: Map<String, String>): String = when (type) {
"card"     -> "Card ending ${details["last4"]}"
"bank"     -> "Bank transfer to ${details["iban"]}"
"cash"     -> "Cash"
else       -> "Unknown"
}
```

**Language-agnostic rule:** If a concept has a finite set of shapes, model them as
a sum type (sealed class/interface, enum, tagged union, Rust enum, TypeScript
discriminated union). Never model them as a string tag + untyped payload.

---

## Principle 2: Validate at construction, not at use

An invalid instance must never exist. Push all invariant checks into the
constructor or factory method. Downstream code should never need to re-validate.

Layer validation through interface inheritance so each level adds exactly one
constraint and delegates upward.

```kotlin
// Structural contract — every value type implements this
interface Validateable {
fun validate(): Validateable
}

// Layered interfaces — each adds one invariant
interface Named : Validateable {
val value: String

interface NonBlank : Named {
override fun validate() = apply {
require(value.isNotBlank()) {
"${this::class.simpleName} must not be blank"
}
}
}

interface Composable : NonBlank {
override fun validate() = apply {
super<NonBlank>.validate()
require(SEPARATOR !in value) {
"${this::class.simpleName} must not contain '$SEPARATOR': '$value'"
}
}
}
}

// Concrete value type — validated at construction, zero-cost at runtime
@JvmInline
value class TenantId(override val value: String) : Named.Composable {
init { validate() }
override fun toString(): String = value
}

// Private constructor + factory for complex invariants
@JvmInline
value class OrderId private constructor(val raw: String) {
companion object {
fun create(tenant: TenantId, sequence: Long): OrderId {
require(sequence > 0) { "Sequence must be positive" }
return OrderId("${tenant.value}::$sequence")
}
fun parse(raw: String): OrderId {
require(raw.count { it == ':' } == 2) { "Malformed OrderId: $raw" }
return OrderId(raw)
}
}
override fun toString(): String = raw
}
```

**Language-agnostic rule:** The type's constructor is the single gatekeeper of
validity. If you can write `val x = SomeType(bogusInput)` and get a live instance,
the design is broken. Use private constructors + factory methods, `init` blocks,
or the language's equivalent (Rust `pub fn new() -> Result<Self>`, TypeScript
branded types with assertion functions).

---

## Principle 3: Single source of truth for every invariant

If an encoding format, separator, default value, or structural rule exists, exactly
one site in the codebase defines it. All consumers delegate to that site.

```kotlin
// GOOD — one object owns the encoding rule
internal object CompositeEncoding {
const val SEPARATOR = "::"

fun encode(prefix: String, parts: List<String>): String {
require(prefix.isNotBlank()) { "Prefix must not be blank" }
parts.forEachIndexed { i, p ->
require(SEPARATOR !in p) { "Part[$i] must not contain '$SEPARATOR': '$p'" }
}
return (listOf(prefix) + parts).joinToString(SEPARATOR)
}

fun split(encoded: String): List<String> = encoded.split(SEPARATOR)
}

// BAD — separator literal repeated in three files
// file A: val id = "$prefix::$name"
// file B: val parts = id.split("::")
// file C: require(!name.contains("::"))
```

**Language-agnostic rule:** If changing a rule requires edits in N places, and the
compiler/type-checker does not force all N, the design is wrong. Extract the rule
into a single owner. Constants, encoding logic, and defaults each get exactly one
definition site.

---

## Principle 4: Sealed hierarchies map domain shapes to behavior

Use sealed types with type parameters to enumerate the legal shapes a value can
take. Dispatch on the sealed subtypes — never on strings, enums-as-tags, or
convention.

```kotlin
// Domain shapes — compiler-enforced exhaustiveness
sealed interface ConfigValue {
sealed interface Primitive<V> : ConfigValue {
val value: V
}

@JvmInline value class Text(override val value: String) : Primitive<String>
@JvmInline value class Flag(override val value: Boolean) : Primitive<Boolean>
@JvmInline value class Count(override val value: Int) : Primitive<Int>

data class Sequence<E>(val items: List<E>) : ConfigValue
data class Composite(val fields: Map<String, ConfigValue>) : ConfigValue
}

// Serialization layer — exhaustive when, no string dispatch
fun serialize(cv: ConfigValue): JsonElement = when (cv) {
is ConfigValue.Text      -> JsonPrimitive(cv.value)
is ConfigValue.Flag      -> JsonPrimitive(cv.value)
is ConfigValue.Count     -> JsonPrimitive(cv.value)
is ConfigValue.Sequence<*> -> JsonArray(cv.items.map { /* ... */ })
is ConfigValue.Composite -> JsonObject(cv.fields.mapValues { serialize(it.value) })
}
```

**Language-agnostic rule:** If you have a `when`/`switch`/`match` that branches on
a string or integer tag to select behavior, replace it with a sum type whose
variants carry their own data. The dispatch mechanism should be the type system.

---

## Principle 5: Metadata belongs to the type, not beside it

If a type has associated metadata (description, help text, default value, display
name), that metadata must be derivable from or attached to the type — not stored
in a parallel data structure connected by string keys.

```kotlin
// GOOD — each command variant carries its own metadata
sealed interface Command {
val meta: CommandMeta

data class Greet(val name: PersonName) : Command {
override val meta = CommandMeta(
path = listOf("greet"),
summary = "Greet a person by name",
)
}

data class Quit(val force: Boolean) : Command {
override val meta = CommandMeta(
path = listOf("quit"),
summary = "Exit the application",
)
}
}

// BAD — parallel list matched by string path
val catalog = listOf(
CommandMeta(path = listOf("greet"), summary = "Greet a person by name"),
CommandMeta(path = listOf("quit"),  summary = "Exit the application"),
)
// ... in another file ...
fun parse(path: List<String>, args: Map<String, String>): Command = when (path) {
listOf("greet") -> Command.Greet(PersonName(args["name"]!!))
listOf("quit")  -> Command.Quit(args["force"] == "true")
else -> error("unknown")
}
```

**Language-agnostic rule:** If adding a new variant requires synchronized edits in
N disconnected locations with no compiler enforcement, the metadata is detached.
Attach it to the type or use a registry pattern where registration is enforced at
the type-definition site.

---

## Principle 6: Explicit string representation for wrapper types

Language-specific but critical in Kotlin/Java/Scala: wrapper types that participate
in logging, serialization, or string interpolation must explicitly override
`toString()` to return the inner value, not the compiler-generated
`ClassName(value)` form.

```kotlin
@JvmInline
value class Email(val address: String) {
override fun toString(): String = address  // not "Email(alice@example.com)"
}
```

In other languages: ensure `Display`/`__str__`/`toString` produces the domain
value, not a debug representation, for types used in user-facing or wire contexts.

---

## Principle 7: Isolate top-level production types by file

Every non-private top-level production type owns a file with the same name.
Apply this to classes, data classes, value classes, enum classes, annotation
classes, sealed roots, interfaces, fun interfaces, and named object
declarations.

Keep declarations nested when the owner is semantically meaningful: direct
sealed variants remain beneath their sealed root, companion objects remain
with their class, and anonymous objects remain at their use site. A tightly
coupled private implementation helper may remain with its owner when extracting
it would falsely advertise a reusable package-level concept.

Top-level functions, extension functions, and properties follow semantic
ownership rather than a mechanical one-declaration rule. Tests may keep private
fixtures and scenario helpers beside the test that owns them.

```kotlin
// GOOD — OrderId.kt
@JvmInline
value class OrderId private constructor(val value: String) {
companion object {
fun parse(raw: String): OrderId = OrderId(raw)
}
}

// GOOD — PaymentMethod.kt; variants share the sealed root's closed world
sealed interface PaymentMethod {
data class Card(val last4: CardLast4) : PaymentMethod
data object Cash : PaymentMethod
}

// BAD — PaymentTypes.kt groups independent package-level concepts
@JvmInline value class OrderId(val value: String)
@JvmInline value class CustomerId(val value: String)
data class PaymentReceipt(val order: OrderId, val customer: CustomerId)
```

**Language-agnostic rule:** Give each independently addressable production
type its own same-named source file. Keep closed variants or private helpers
with the type that owns them; do not use topic-named files as containers for
unrelated public or internal types.

---

## Anti-patterns to reject

| # | Anti-pattern | Fix |
|---|---|---|
| 1 | **Parallel metadata structures** — a list/map that mirrors a sealed hierarchy, connected only by string keys | Attach metadata to the type via an abstract property or companion |
| 2 | **Bag-of-strings intermediate representations** — `Map<String, String>` indexed by bare string literals | Define a typed intermediate (data class with named, validated fields) |
| 3 | **Scattered defaults** — a default value declared in metadata AND separately in a parser/handler | One definition site; derive everywhere else |
| 4 | **Flat sealed hierarchies** — 15 of 20 variants share a shape but no common sub-interface | Extract a sub-interface for the shared shape |
| 5 | **Validation at use-site** — `if (x.isBlank()) throw ...` at the call site instead of in the constructor | Move the check into the type's `init`/factory |
| 6 | **String-based dispatch** — `when (tag)` / `switch (kind)` where a sealed `when` would compile-check | Replace the tag with a sealed subtype |
| 7 | **Topic-named type containers** — multiple independent top-level production types share one file | Move each type to its same-named file; keep only nested variants and private owned helpers together |

---

## Self-audit checklist

After generating code, verify:

- [ ] Can I construct an invalid instance of any type? → Fix the constructor.
- [ ] Does adding a new sealed variant cause a compile error everywhere it needs
handling? → If not, dispatch is string-based; refactor.
- [ ] Is any string literal used as a lookup key in more than one file? → Extract
a constant or, better, a type.
- [ ] Does any `when`/`switch` branch on a string that could branch on a type? → Refactor.
- [ ] Are defaults defined in exactly one place? → Consolidate.
- [ ] Does every wrapper type have an explicit `toString()`? → Add it.
- [ ] Does every non-private top-level production type own a same-named file? → Isolate it unless it is a nested variant or private owned helper.
