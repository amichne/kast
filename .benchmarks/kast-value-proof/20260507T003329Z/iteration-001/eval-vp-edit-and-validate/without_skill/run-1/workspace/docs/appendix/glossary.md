# Glossary

This glossary defines the core terms used across the current documentation
set.

## Terms

The definitions here are short on purpose. Each term points back to the model
used by the current repo.

| Term | Meaning |
| --- | --- |
| Namespace | A team-owned container for feature declarations and namespace-local runtime state |
| Feature | A typed handle bound to a value type, a context type, and a namespace |
| Context | The runtime input passed to `evaluate(...)` or `explain(...)` |
| Axis | A descriptor for a domain-specific targeting dimension |
| Axis value | An enum entry that lives on an axis and can be targeted by rules |
| Rule | A conditional value assignment paired with targeting criteria |
| Ramp-up | Gradual rollout based on deterministic bucketing and stable identity |
| Configuration | Runtime snapshot of flags plus metadata |
| Namespace registry | Runtime surface for load, rollback, disable, and enable operations |
| Parse result | The success or failure wrapper returned by the JSON trust boundary |
| Parse error | Structured failure reason from JSON decoding |
| Konstrained | Contract for custom values that can round-trip through the JSON layer |

## Next steps

If you need more than a definition, the FAQ answers the most common adoption
questions and objections directly.

- [Open the FAQ](faq.md)

