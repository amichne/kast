# DSLs And Generics

Use for receiver DSLs, variance, reified generics, and generic API shape.

- Use a DSL only when nested structure or staged configuration is clearer than
  named arguments.
- Add `@DslMarker` when nested receivers can be confused.
- Keep mutable DSL state private and return immutable results.
- Prefer concrete domain types when the domain is known.
- Use `out` for producers and `in` for consumers.
- Avoid `List<*>` and `Any` in public APIs unless type erasure is the point.
- Use `inline reified` only when call-site type tokens are genuinely needed.
