# Parse boundary

The JSON boundary is strict by design. External text is not trusted until it
has been decoded into a full `Configuration`, and failures do not partially
mutate the live namespace.

## What is guaranteed

`Namespace.fromJson(json)` has two outcomes.

- `ParseResult.Success` with a fully decoded configuration that is then loaded
  into the namespace
- `ParseResult.Failure` with a typed `ParseError`

There is no success path that applies only some of the payload.

## Failure classes

The current parse boundary can surface failure types such as:

- `ParseError.InvalidJson`
- `ParseError.FeatureNotFound`
- `ParseError.InvalidSnapshot`
- `ParseError.UnknownField`
- `ParseError.MissingRequired`
- `ParseError.InvalidValue`

These errors make the failure mode explicit without requiring callers to decode
ad hoc exception text.

## Evidence in the repo

The JSON tests prove that malformed JSON, unknown feature keys, and invalid
custom values are rejected while the previous namespace state remains intact.

- `NamespaceJsonTest`
- `ConfigurationCodecTest`
- `ParseResultTest`

## Next steps

If you want the exact APIs and JSON fields involved, the reference section
collects them in one place.

- [Open the reference section](../reference/index.md)

