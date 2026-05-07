# Scope

- This file applies to `konditional-types/`.

## Work here

- Validate with `./gradlew :konditional-types:test`.

## Edit rules

- This module is the dependency floor for the published stack. Keep engine and JSON implementation concerns out of it.
- `src/main/kotlin/io/amichne/konditional/**` holds shared identifiers, contexts, parse results, and `Konstrained`
  contracts.
- `src/main/kotlin/io/amichne/kontracts/**` also lives here, so schema and value DSL changes must stay engine-agnostic.
- If you change shared ids, parse failures, or context primitives, follow with
  `./gradlew :konditional-engine:test :konditional-json:test`.
