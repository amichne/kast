# Scope

- This file applies to `build-logic/`.

## Work here

- Validate with `./gradlew -p build-logic test`.
- Shared dependency versions come from `../gradle/libs.versions.toml`.

## Edit rules

- `src/main/kotlin/*.gradle.kts` defines the precompiled plugin ids. Renaming those files changes the ids consumed by module `build.gradle.kts` files.
- Helper code under `src/main/kotlin/io/amichne/konditional/gradle/` is authoritative; do not edit `build-logic/build/` or generated Kotlin DSL accessors.
- If you change publishing or test conventions here, update the consuming module build files and matching root Makefile or workflow entrypoints when needed.
