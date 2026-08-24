# Architecture policy projection

This directory owns the deterministic JSON projection of the admitted architecture policy.

- Project the typed policy without reinterpreting module roles, dependency edges, or effects.
- Fixed policy documents use `@Serializable` types and explicit generated `.serializer()`
  factories; do not render JSON fields, arrays, escaping, or discriminators by hand.
- Preserve deterministic collection order so regenerated checked policy is byte-stable.

Run `./gradlew -p build-logic test --tests support.architecture.projection.ArchitectureProjectionTest`.
