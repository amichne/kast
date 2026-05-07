# Scope

- This file applies to `konditional-engine/`.

## Work here

- Validate with `./gradlew :konditional-engine:test`.

## Edit rules

- This module owns namespace DSL, evaluation, registry state, and shared test fixtures. Keep JSON parsing concerns out
  of it.
- Preserve deterministic evaluation, namespace isolation, and whole-snapshot atomicity.
- If you change public DSL, evaluation semantics, or registry behavior, also run
  `./gradlew :konditional-json:test :smoke-test:test`.
