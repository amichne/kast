# Kotlin Type Safety Guide

Use this file as a router when you need stronger type-system guidance but the
exact topic is not obvious yet. Prefer loading one focused file below instead
of the whole type-safety corpus.

## Read this when

- `types-domain-modeling.md`: model domain constraints with value classes,
  sealed types, nullability elimination, and immutable products.
- `types-dsls-and-generics.md`: design DSL receivers, variance, reified
  generics, or context-based APIs.
- `types-errors-and-testing.md`: shape typed outcomes, review type-safety
  anti-patterns, and choose testing techniques that prove compile-time
  guarantees.

## Loading rule

Start with the narrowest matching file. Read multiple files only when the task
crosses domain modeling, advanced generics, and verification concerns.
