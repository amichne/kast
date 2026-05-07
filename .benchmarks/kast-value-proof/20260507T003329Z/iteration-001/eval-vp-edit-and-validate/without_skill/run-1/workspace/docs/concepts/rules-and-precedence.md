# Rules and precedence

Rules are the conditional part of a flag definition. Each rule pairs a
targeting expression with a value, and evaluation chooses the highest-priority
eligible rule before falling back to the default value.

## What a rule can target

The public DSL can target standard context dimensions and custom axes.

- locale
- platform
- version range
- axis selections through `constrain(...)`
- guarded context narrowing
- ramp-up and allowlists

Those targeting elements build a structural specificity score that drives
precedence.

## How precedence works

`FlagDefinition` sorts candidate values by descending `rule.specificity()`
before evaluation. More specific rules outrank less specific ones.

That means a rule that targets several dimensions will usually win over a
broader rule that targets only one. If two rules are equally specific, keep
their intent clearly separated instead of relying on subtle tie behavior.

## What happens during evaluation

Evaluation walks the rules in precedence order, finds the first rule whose
targeting matches the context, checks ramp-up eligibility, and returns the
resolved value. If no eligible rule produces a value, the feature default is
returned.

This same decision path is what `explain(...)` exposes through diagnostics.

## Next steps

The next concept page shows how those decisions relate to runtime state and
whole-snapshot operations.

- [Read about runtime snapshots](runtime-snapshots.md)

