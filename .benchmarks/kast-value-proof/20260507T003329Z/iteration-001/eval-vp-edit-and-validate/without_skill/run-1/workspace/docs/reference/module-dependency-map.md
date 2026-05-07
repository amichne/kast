# Module dependency map

This page helps you pick the narrowest dependency surface for the capability
you want to add.

## Published modules

The current repo exposes three meaningful library modules for consumers.

| Module | Use it for | Notes |
| --- | --- | --- |
| `konditional-types` | Shared identifiers, contexts, axes, parse results, and `Konstrained` contracts | Useful when you need shared domain or boundary types without the engine |
| `konditional-engine` | Namespaces, feature declarations, `evaluate(...)`, `explain(...)`, and runtime registries | The default first dependency for application code |
| `konditional-json` | Snapshot export and strict JSON import through `toJson()` and `fromJson(json)` | Add when external configuration becomes necessary |

## Typical choices

Most adopters start with one of these shapes.

- `konditional-engine` only for code-defined flags
- `konditional-engine` plus `konditional-json` for runtime snapshots
- `konditional-types` in a shared module when multiple modules need the same
  contexts, axes, or custom value contracts

## Non-library module

`smoke-test` is a verification module in the repo. It is not part of the
consumer dependency story.

## Next steps

After you choose the module surface, the API page is the fastest way to see
the key entry points inside those modules.

- [Open the API surface](api-surface.md)

