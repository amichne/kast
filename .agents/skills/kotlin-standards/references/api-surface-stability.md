# API Surface Stability

Use when changing public, internal, or cross-module APIs.

- Default to `private` or `internal`.
- Keep constructors private when parsing or invariants are required.
- Expose read-only collection interfaces.
- Avoid implementation classes in public signatures.
- Treat public data class property changes as behavior changes.
- Adding a sealed subtype can break exhaustive `when` callers.
- Prefer deprecation with a migration path over removal.
- Use opt-in annotations for experimental APIs that callers may build around.
