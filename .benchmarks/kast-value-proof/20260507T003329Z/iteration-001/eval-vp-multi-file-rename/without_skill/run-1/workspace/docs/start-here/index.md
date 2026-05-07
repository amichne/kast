# Start here

This section is the evaluator hub for Konditional. Read it when you want to
decide whether the library fits your team, your architecture, and your
operational constraints before you invest in a migration.

## What to expect

The pages in this section stay high level on purpose. They focus on value,
fit, trade-offs, and adoption posture rather than implementation detail.

- [Why Konditional](why-konditional.md) explains the architectural problem the
  library is trying to solve.
- [When it fits](when-it-fits.md) helps you decide whether the current shape is
  a good match for your team.
- [Trade-offs and non-goals](trade-offs-and-non-goals.md) is the blunt version
  of what Konditional is not trying to be.
- [Comparison](comparison.md) places Konditional beside common alternatives.
- [Adoption path](adoption-path.md) gives a staged rollout path for current-repo
  Konditional.

## Current module map

The evaluator story is grounded in the modules that exist today, not a future
platform roadmap.

| Module | What it gives you |
| --- | --- |
| `konditional-types` | Shared context types, identifiers, parse results, and `Konstrained` contracts |
| `konditional-engine` | `Namespace`, typed features, `evaluate`, `explain`, and atomic runtime registries |
| `konditional-json` | `toJson`, `fromJson`, strict snapshot decode, and `ParseError` boundary failures |
| `smoke-test` | Integration verification for the repo itself |

## Next steps

Once the evaluator path answers the value and fit questions, move into the
guided install and first namespace flow.

- [Continue to the quickstart](../quickstart/index.md)
- [Skim the core concepts](../concepts/index.md)

