# Namespace isolation

Namespaces are isolated runtime domains. The same feature key can appear in
two namespaces without causing cross-talk because evaluation is always rooted
in the owning namespace.

## What is guaranteed

Isolation shows up in two places.

- The feature identity includes the namespace.
- Each namespace fronts its own registry state and snapshot history.

That means loading or rolling back one namespace does not rewrite another
namespace's evaluation surface.

## Why this matters operationally

Isolation is what makes incremental adoption practical. One team can add
Konditional to its own module and runtime path without forcing every other
team into the same namespace catalog or rollout cadence.

## Evidence in the repo

The repo includes focused tests that declare the same-looking features in
separate namespaces and prove that defaults and loaded values remain distinct.

- `NamespaceBehaviorTest`
- `NamespaceRuntimeTest`
- `Namespace`
- `NamespaceRegistry`

## Next steps

The next guarantee explains how namespace-local runtime updates remain
coherent for concurrent readers.

- [Read about atomic snapshots](atomic-snapshots.md)

