# Namespaces

`Namespace` is the primary ownership boundary in Konditional. It groups
feature declarations, carries the namespace identifier, and fronts the runtime
registry used for load and rollback operations.

## What a namespace owns

A namespace owns three related things.

- The declared feature properties
- The namespace identifier used in feature IDs
- The runtime registry surface for configuration operations

Because those concerns stay together, a team can start local without inventing
a shared global catalog.

## Why namespaces matter

Namespaces are how Konditional keeps similar feature names from colliding. Two
different namespaces can both declare `enabled` and still evaluate
independently because the full feature identity includes the namespace.

That isolation is visible both at the type level and at runtime.

## Declared definitions and loaded state

A namespace always has its declared defaults available. When you later load a
runtime `Configuration`, the namespace combines the declared definitions with
the loaded registry state for evaluation.

That means the namespace is useful before remote config and remains the same
surface after remote config.

## Next steps

Once you are comfortable with namespace ownership, the next useful concept is
what actually lives inside a namespace: typed features.

- [Read about typed features](typed-features.md)

