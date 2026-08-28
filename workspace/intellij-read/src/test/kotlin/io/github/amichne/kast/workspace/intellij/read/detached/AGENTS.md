# Detached-model proof guide

This directory owns the portable KVP-016 fixtures and detached-model proof. Sources retain the
`io.github.amichne.kast.workspace.intellij.read` package so they can exercise the internal pure
boundary without widening production visibility.

## Invariants

- Exercise `DetachedIdeWorkspaceModel.admit` with primitive-only `DetachedModelObservation` data.
- Keep these tests runnable on Java 21. Do not import, reference, reflect on, or classload
  `LiveDetachedModelCapture` or any IntelliJ/Gradle model type.
- Every named malformed, missing, moved, ambiguous, or oversized observation must return
  `DetachedModelCapture.Rejected`; no case may be normalized, truncated, deduplicated, repaired, or
  manufactured into success.
- Positive proof must compare the exact generated report bytes, exercise deterministic ordering,
  mutate raw fixture collections after capture, and attempt mutation through Java collection casts.
- Classpath URL refinement proof must retain exact `file`, `jar`, and `jrt` roots with raw VFS path
  punctuation and reject unsupported, opaque, authority-bearing, aliased, trailing-separator, and
  malformed spellings.
- The receipt-bound refinement contract must prove oversized text wins before semantic scans and
  that exact-root proof construction is private and consumed by model construction.
- Recursively inspect the generic public model surface. Reject IntelliJ, Gradle, Kotlin-platform,
  `Any`, callback, generic open authority, and mutable collection types.
- Keep live API bytecode proof separately owned, require disposal/open/initialization checks, and
  never classload its IDEA 262 contract class.

## Focused proof

Run:

```shell
./gradlew :workspace:intellij-read:test --tests '*DetachedModelNegativeTest'
./gradlew :workspace:intellij-read:test --tests '*DetachedModelTest'
./gradlew :workspace:intellij-read:test --tests '*DetachedClasspathUrlRefinementTest'
```
