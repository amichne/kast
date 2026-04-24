# Parse, Don't Validate

Use this when external data enters the system.

## Boundary Rule

Treat CLI args, files, JSON, database rows, environment variables, network
payloads, and user input as untrusted. Convert them once into trusted domain
types, then keep raw primitives out of core logic.

## Shape

```kotlin
@JvmInline
value class ProjectName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<ProjectName> =
            raw.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { Result.success(ProjectName(it)) }
                ?: Result.failure(IllegalArgumentException("project name is blank"))
    }
}
```

Use the repository's existing typed error pattern when one exists. The example
uses `Result` only as a standard-library fallback.

## Review

- Raw input should not flow past the boundary.
- Failure should be observable and testable.
- Parsing should normalize once, not repeatedly re-check the same invariant.
