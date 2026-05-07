# Type safety

Konditional gives you meaningful type safety at the feature boundary. The key
gain is that the feature handle, the value type, and the owning namespace are
bound together in Kotlin code.

## What is guaranteed

These guarantees are visible in normal feature declaration and evaluation.

- A feature property returns the type you declared for it.
- A feature stays attached to its owning namespace.
- Callers do not pass string keys into `evaluate(...)`.
- Enums and `Konstrained` values preserve their declared shapes across the
  supported APIs.

## What is not fully compile-time yet

Not every supporting domain constraint is currently parse-first. Some value
objects still enforce invariants with runtime checks rather than typed parse
results. That means Konditional already removes several illegal states from the
feature model, but it does not yet eliminate every runtime precondition in the
supporting types.

## Evidence in the repo

The type surface is anchored in the `Feature<T, C, M>` model and in the
namespace property delegates that construct those features. The JSON round-trip
tests also prove that typed values survive export and re-import when the input
is valid.

Useful classes and tests include:

- `Feature`
- `Namespace`
- `ParseResult`
- `ConfigurationCodecTest`
- `NamespaceJsonTest`

## Next steps

Type safety is only part of the story. The next page explains why evaluation
itself stays stable for the same inputs.

- [Read about determinism](determinism.md)

