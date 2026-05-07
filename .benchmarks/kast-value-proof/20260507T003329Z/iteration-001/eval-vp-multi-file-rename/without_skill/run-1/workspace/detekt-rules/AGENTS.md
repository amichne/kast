# Scope

- This file applies to `detekt-rules/`.

## Work here

- Validate with `./gradlew -p detekt-rules test`.

## Edit rules

- This module is standalone and is not included by the active root `settings.gradle.kts` graph.
- Edit sources under `src/main/kotlin/`; do not edit `detekt-rules/build/`.
- If you change rule ids or the rule-set provider, check matching config under `../config/detekt/`.
- Do not assume `make test` covers this module.
