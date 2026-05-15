# Type-Safe Domain Modeling

Use for value classes, parsed models, ADTs, nullability removal, invariant wrappers, and immutability.

Start from the invalid state at the call site. Add the smallest type that makes that state unrepresentable.

- Value class: meaningful single-field primitives such as IDs, names, ports, paths, offsets, line numbers, or hashes.
- Private constructor + factory: use `parse`, `of`, or `from` when the value must be normalized or validated exactly
  once before it becomes trusted.
- Enum: stable closed labels with no per-case data.
- Sealed hierarchy: closed states, commands, outcomes, queries, and error families with per-case data. Design so callers
  use exhaustive `when`.
- Data class: immutable records that can be copied and compared.
- Invariant wrapper: reusable types such as non-empty collections, non-blank strings, bounded numbers, or normalized
  text when the same invariant recurs.
- Nullable type: only when absence is itself a valid domain value.
- Mutation: keep inside builders, adapters, caches, or measured hot paths.

Prefer parsed or validated models downstream. Once a boundary has converted raw input into trusted types, stop passing
partially checked primitives through the core.

Collapse boolean-plus-nullable control combinations into one typed concept. A sealed variant or focused value type is
usually clearer than flags that imply a hidden state machine.
