# Scope

- This file applies to `konditional-json/`.

## Work here

- Validate with `./gradlew :konditional-json:test`.

## Edit rules

- This is the untrusted JSON boundary on top of engine types. Failed decode must return typed failures without partial
  application.
- `src/main/kotlin/io/amichne/konditional/internal/serialization/**` contains wire models and Moshi adapters; keep
  schema and materialization rules explicit.
- If you change snapshot shape, extraction, or codec behavior, also run `./gradlew :smoke-test:test` and update
  `docs/reference/` when the public format changes.
