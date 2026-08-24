# Symbol protocol composition

This directory owns canonical symbol discovery admission and the boundary conversion between
generated selector-token documents and compiler-grounded selector types.

- Decode each fixed selector schema with its dedicated `@Serializable` document and explicit
  generated `.serializer()` factory; never walk JSON elements or assemble maps.
- Refine decoded primitives immediately into exact workspace, generation, file, range, identity,
  and scope types. Preserve every gained proof in the returned selector.
- Unknown kinds, malformed variants, incomplete scopes, and mismatched evidence fail closed as
  exhaustive selector admission data.

Run `./gradlew :runtime:composition:test`, then `./gradlew :runtime:composition:check`.
