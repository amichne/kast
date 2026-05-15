# API Builders And Configuration

Use this for configuration objects, builders, and Kotlin setup DSLs.

- Start with immutable data classes and named arguments.
- Add a builder only for staged, nested, conditional, validated, or Java-facing
  construction.
- Keep mutable state private to the builder and return an immutable result.
- Validate at `build()` or the parse boundary; do not let half-valid objects
  escape.
- Use receiver lambdas only when they make the call site clearer than named
  arguments.
- Add `@DslMarker` when nested receivers could be confused.
- Keep a builder in the owning type's file unless it has independent ownership.
