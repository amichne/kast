# Semantic runtime distribution contract guide

`:distribution:contract` owns host-neutral, proof-carrying runtime identity and manifest values.

## Boundaries

- Production depends only on `:kernel` and serialization.
- Raw manifest JSON and runtime-source text are admitted once here.
- No filesystem, network, archive, process, IntelliJ, or Gradle effects are permitted.

## Verification

Run `./gradlew :distribution:contract:test`.
