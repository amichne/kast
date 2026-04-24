# Type-Safe Domain Modeling

Use for value classes, sealed state, nullability removal, and immutability.

- Value class: meaningful primitives such as IDs, names, ports, paths, and
  percentages.
- Enum: stable closed labels with no per-case data.
- Sealed hierarchy: closed states, outcomes, commands, and events with per-case
  data.
- Data class: immutable records that can be copied.
- Nullable type: only when absence is a real domain value.
- Mutation: keep inside builders, adapters, caches, or measured hot paths.

Ask what invalid value can still be constructed, then add only the type needed
to remove it.
