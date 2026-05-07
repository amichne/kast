# API surface

This page lists the main user-facing APIs in the current repo. It is grouped
by capability rather than by source file.

## Declaration and evaluation

These types and functions are the center of day-to-day use.

| API | Purpose |
| --- | --- |
| `Namespace` | Owns feature declarations, namespace identity, and runtime registry access |
| `Feature<T, C, M>` | Typed handle bound to a value type, context type, and namespace |
| `boolean`, `string`, `integer`, `double`, `enum`, `custom` | Namespace delegates for declaring features |
| `evaluate(context)` | Returns the typed feature value for the given context |
| `explain(context)` | Returns the evaluation result plus diagnostics for the same decision path |

## Context and targeting

These types describe runtime inputs and domain dimensions.

| API | Purpose |
| --- | --- |
| `Context` | Base evaluation input |
| `Context.LocaleContext` | Opt-in locale targeting |
| `Context.PlatformContext` | Opt-in platform targeting |
| `Context.VersionContext` | Opt-in version targeting |
| `Context.StableIdContext` | Opt-in stable identity for rollout and allowlists |
| `Axis<T>` | Descriptor for a domain-specific targeting dimension |
| `AxisValue<T>` | Enum contract for values that live on an axis |
| `axes(...)` | Helper for constructing grouped axis selections on a context |
| `RampUp` | Value type used by rollout-aware rules |

## Runtime state

These types describe the namespace-local runtime model.

| API | Purpose |
| --- | --- |
| `Configuration` | Runtime flag map plus metadata |
| `ConfigurationMetadata` | Optional version, source, and generation timestamp |
| `NamespaceRegistry` | Load, rollback, disable, enable, and flag lookup contract |
| `InMemoryNamespaceRegistry` | Default in-memory registry with atomic snapshot replacement |

## Boundary and serialization

These types describe the JSON boundary and custom value contract.

| API | Purpose |
| --- | --- |
| `Konstrained` | Contract for custom values that can round-trip through the JSON layer |
| `Namespace.toJson()` | Export the current namespace snapshot |
| `Namespace.fromJson(json)` | Strictly decode a snapshot and load it on success |
| `ParseResult.Success` / `ParseResult.Failure` | Typed success or failure from the parse boundary |
| `ParseError` | Structured failure model for JSON boundary errors |

## Diagnostics

These types describe what `explain(...)` surfaces today.

| API | Purpose |
| --- | --- |
| `EvaluationDiagnostics<T>` | Snapshot of one evaluation result and its decision path |
| `EvaluationDiagnostics.Decision` | Closed set of evaluation outcomes |
| `EvaluationDiagnostics.RuleMatch` | Matched rule details plus bucket metadata |
| `BucketInfo` | Deterministic rollout bucket metadata for the matched rule |

## Next steps

If you need the exact JSON fields that back `toJson()` and `fromJson(json)`,
open the snapshot format page next.

- [Open the snapshot JSON format](snapshot-json-format.md)

