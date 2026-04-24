# Kotlin Antipatterns

Use when reviewing code for correctness or maintainability risks.

- Type safety: `!!`, unchecked `as`, raw `Any`, star projections in public APIs,
  stringly typed IDs, and constructors that accept invalid data.
- Layout: `utils`, `common`, cross-cutting `extensions`, redundant type
  prefixes, or interfaces split from their only small implementations.
- State: public `var`s, mutable data classes, mutable collections crossing
  boundaries, singleton state, ambient context, and unstructured coroutines.
- APIs: boolean traps, nullable control flags, generic `Manager`/`Helper` names,
  and expected errors represented by `null` or generic exceptions.
- Tests: implementation-shaped tests, coverage-only tests, and missing boundary
  failure tests.
