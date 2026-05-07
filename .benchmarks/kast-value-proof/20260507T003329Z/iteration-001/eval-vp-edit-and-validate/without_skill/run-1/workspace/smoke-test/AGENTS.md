# Scope

- This file applies to `smoke-test/`.

## Work here

- Validate with `./gradlew :smoke-test:test`.

## Edit rules

- This module exists to cover the public end-to-end path across engine and JSON.
- Keep tests focused on exported APIs and observable behavior, not internal implementation details.
- This is a verification module only; it is not part of the published consumer coordinates.
