# Runtime snapshots

Konditional keeps runtime state at the namespace level. The important unit is
the whole namespace snapshot, not an individual flag mutation.

## The runtime model

`Configuration` holds the runtime flag map and optional `ConfigurationMetadata`
such as version, source, and generation time. `NamespaceRegistry` is the
abstraction that loads and serves that configuration during evaluation.

The default runtime implementation is `InMemoryNamespaceRegistry`.

## Whole-snapshot operations

The registry surface is small on purpose.

- `load(config)`
- `rollback(steps = 1)`
- `disableAll()`
- `enableAll()`

Each of these operations applies at the namespace snapshot boundary rather than
patching individual flags in place.

## Why atomicity matters

`InMemoryNamespaceRegistry` stores the current snapshot behind an
`AtomicReference` and synchronizes writes so readers observe either the whole
previous snapshot or the whole new snapshot.

That design is what keeps concurrent reads coherent during load and rollback.

## Next steps

The last concept page explains how external JSON enters that runtime model and
what kind of failures the boundary returns.

- [Read about the JSON boundary](json-boundary.md)

