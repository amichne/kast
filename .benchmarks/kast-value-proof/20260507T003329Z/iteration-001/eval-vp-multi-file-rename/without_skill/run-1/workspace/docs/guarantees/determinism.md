# Determinism

Konditional aims for deterministic evaluation. The same context and the same
namespace snapshot should produce the same result every time.

## What that means

Determinism here is not just about final values. It also includes the
diagnostic path used by `explain(...)`.

- `evaluate(...)` and `explain(...)` follow the same rule resolution path.
- Rule precedence is derived structurally from targeting specificity.
- Ramp-up bucketing is stable for the same salt, feature key, and stable ID.

## Why rollout stays stable

Ramp-up uses a stable bucket calculation rather than a random sample. When the
stable ID and the namespace state stay fixed, the computed bucket stays fixed
too.

That is what lets teams reason about rollout behavior instead of treating it
as a moving target.

## Evidence in the repo

The repo currently exercises deterministic behavior in both basic evaluation
tests and rollout-oriented tests.

- `NamespaceBehaviorTest`
- `NamespaceRuntimeTest`
- `Bucketing`
- `FeatureEvaluation`

## Next steps

The next guarantee explains how that deterministic behavior stays scoped to a
single namespace instead of leaking across teams.

- [Read about namespace isolation](namespace-isolation.md)

