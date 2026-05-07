# JSON boundary

The JSON layer is the trust boundary for external configuration. It is where
untrusted text becomes a typed `Configuration` or a typed failure.

## Public entry points

The user-facing entry points are the namespace extensions in
`konditional-json`.

- `Namespace.toJson()`
- `Namespace.fromJson(json)`

`toJson()` exports the current namespace snapshot. `fromJson(json)` attempts to
decode the payload, loads it on success, and returns `ParseResult`.

## Failure behavior

Failures are explicit and strict. The JSON boundary can return errors such as
`ParseError.InvalidJson`, `ParseError.FeatureNotFound`,
`ParseError.InvalidSnapshot`, `ParseError.UnknownField`,
`ParseError.MissingRequired`, and `ParseError.InvalidValue`.

Those errors describe why decoding failed without partially mutating the live
namespace.

## What stays hidden

The current implementation uses internal serialization machinery, but readers
do not need that detail. The contract that matters is simpler: the namespace
either loads a fully valid snapshot or keeps the previous state and returns a
typed failure.

## Next steps

If you want to apply the boundary in a real workflow, the guides section has a
remote configuration recipe and a custom-value recipe.

- [Read the remote configuration guide](../guides/remote-configuration.md)
- [Read the custom value guide](../guides/custom-konstrained-values.md)

