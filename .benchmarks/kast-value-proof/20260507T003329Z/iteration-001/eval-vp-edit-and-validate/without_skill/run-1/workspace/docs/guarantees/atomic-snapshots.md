# Atomic snapshots

Runtime updates in Konditional happen at the namespace snapshot boundary.
Readers should observe either the previous snapshot or the new snapshot, never
a partially applied mix of both.

## What is guaranteed

For the default in-memory runtime, the important whole-snapshot operations are:

- `load(config)`
- `rollback(...)`
- `disableAll()`
- `enableAll()`

These operations are coordinated around the current namespace snapshot rather
than mutating individual flag entries in place.

## Why this is stronger than per-flag mutation

Per-flag mutation is difficult to reason about under concurrency because a
reader can observe a half-updated world. Whole-snapshot replacement keeps the
state model smaller and makes rollback semantics clearer.

## Evidence in the repo

`InMemoryNamespaceRegistry` uses an `AtomicReference` for the current snapshot
and serializes writes so snapshot transitions remain coherent. The repo also
has concurrent tests that try to catch partial-state observation.

- `InMemoryNamespaceRegistry`
- `NamespaceRuntimeTest`
- `NamespaceAtomicityTest`

## Next steps

The last guarantee explains how malformed external input interacts with that
runtime model.

- [Read about the parse boundary](parse-boundary.md)

