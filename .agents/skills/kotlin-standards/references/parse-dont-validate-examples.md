# Parse, Don't Validate — Concrete Examples

Concrete Kotlin transformations illustrating the "Parse, Don't Validate" discipline from `kotlin-mastery`.

---

## Email — validation to parsing

```kotlin
// BEFORE (validate)
data class Email(val value: String) {
    init {
        require(value.contains("@")) { "Invalid email" }
    }
}

// AFTER (parse)
@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        fun parse(input: String): Result<Email> =
            if (input.contains("@")) Result.success(Email(input))
            else Result.failure(InvalidEmailException(input))
    }
}
```

**What changed**: The type's constructor is private; invalid `Email` instances cannot be constructed. Calling code receives a `Result<Email>` and must handle failure at the boundary.

---

## Config/Timeout — nullable to sealed type

```kotlin
// BEFORE
data class Config(val timeout: Long? = null)

// AFTER
sealed interface Timeout {
    data class Finite(val millis: PositiveLong) : Timeout
    object Infinite : Timeout
}
data class Config(val timeout: Timeout)
```

**What changed**: `null` no longer silently means "infinite". The two valid states are explicit and exhaustively matchable.

---

## User — boolean flags to sealed type

```kotlin
// BEFORE
data class User(val name: String, val isAdmin: Boolean, val isBanned: Boolean)

// AFTER
sealed interface UserStatus {
    data class Active(val name: NonEmptyString) : UserStatus
    data class Admin(val name: NonEmptyString) : UserStatus
    data class Banned(val reason: String) : UserStatus
}
```

**What changed**: The old model allows `isAdmin = true, isBanned = true` simultaneously — an illegal state. The sealed hierarchy makes co-occurrence impossible.

---

## Key Techniques

| Technique | When to use |
|-----------|-------------|
| `@JvmInline value class` with private constructor | Zero-cost wrapping of primitives (IDs, tokens, emails) |
| `sealed interface` hierarchy | State machines, variants, mutually exclusive states |
| `data class` with private constructor + companion factory | Multi-field invariants requiring coordinated validation |
| `Result<T>` or custom sealed result | Parse outcomes at system boundaries |
| Companion object `parse()` / `of()` factory | Single, explicit entry point that returns typed success/failure |

---

## Anti-Patterns to Identify

- **Primitive obsession**: `String`, `Int`, `Double` where a domain type belongs
- **`require()`/`check()` in `init`**: throws exceptions instead of returning typed errors
- **Boolean flags or nullable fields** encoding state machines
- **Repeated validation** of the same constraint in multiple places
- **Comments explaining invariants** that the type system could enforce
