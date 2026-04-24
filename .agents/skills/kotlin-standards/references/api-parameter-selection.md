# API Parameter Selection

Choose the simplest clear call shape:

1. Plain parameters with clear names.
2. Named arguments with defaults.
3. Small options data class when values travel together.
4. Overloads for common shapes or Java interop.
5. Builder for staged, nested, or conditional construction.

Avoid boolean traps, nullable control flags, `Map<String, Any?>`, long mixed
parameter lists, and overloads that differ only by primitive type. Prefer value
classes, sealed choices, and domain option objects.
