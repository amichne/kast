# Relation service module guide

`:relation:service` owns current-generation admission and compiler-result validation for public
`relation.read`. It does not own live IntelliJ state, native enumeration, traversal, persistence,
transport, workspace transitions, or mutation.

## Invariants

- The exact selector lease must match the current ready publication before and after compiler work.
- Compiler output must retain the request selector, meaning, generation, endpoint orientation,
  occurrence, provenance, bounds, coverage, and continuation binding.
- Complete empty evidence is absence. Qualified empty evidence is only a known minimum of zero.
- No incomplete compiler result may cross the public boundary without a continuation.

## Verification ladder

1. Run `./gradlew :relation:service:test --tests '*RelationServiceTest'`.
2. Run `./gradlew :relation:contract:test :relation:service:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
