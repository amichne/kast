# Evaluation diagnostics

`explain(context)` returns the evaluated value and the deterministic decision
path that produced it. This page summarizes the model at a lookup level.

## Top-level fields

`EvaluationDiagnostics<T>` currently includes these fields.

| Field | Meaning |
| --- | --- |
| `namespaceId` | Namespace identity for the evaluation |
| `featureKey` | Feature key inside that namespace |
| `configVersion` | Version from `ConfigurationMetadata`, if present |
| `mode` | Evaluation mode used by the engine |
| `durationNanos` | Observability timing, not semantics |
| `value` | Final typed value returned by evaluation |
| `decision` | Closed set of evaluation outcomes |

## Decision branches

The `decision` field is one of these branches.

| Branch | Meaning |
| --- | --- |
| `RegistryDisabled` | Namespace-wide kill switch forced the default |
| `Inactive` | Flag inactivity forced the default |
| `Rule` | A rule produced the winning value |
| `Default` | No eligible rule produced a value |

## Rule matches

When the decision involves a rule, the diagnostics include a `RuleMatch`
wrapper with:

- `rule`: the structured rule explanation
- `bucket`: the `BucketInfo` used for rollout reasoning

The rule explanation includes rollout, locale, platform, version, axis, and
specificity metadata so you can inspect the matched path without re-running the
rule logic manually.

## Current caution

The diagnostics type is part of the current returned surface of `explain(...)`,
but it is more operational than the core declaration APIs. Treat it as a
useful inspection model rather than the primary abstraction for application
logic.

## Next steps

If you are validating local workflows around docs, builds, and release, the
last reference page collects those commands.

- [Open build, docs, and release](build-docs-and-release.md)

