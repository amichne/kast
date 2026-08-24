# Semantic runtime distribution contract guide

`:distribution:contract` owns host-neutral, proof-carrying runtime identity and manifest values.

## Boundaries

- Production depends only on `:kernel` and serialization.
- Raw manifest JSON and runtime-source text are admitted once here.
- The closed manifest schema uses dedicated `@Serializable` documents and explicit generated
  `.serializer()` factories; `verifyGeneratedRuntimeManifestSerialization` rejects inferred or
  hand-written adapters.
- No filesystem, network, archive, process, IntelliJ, or Gradle effects are permitted.

## Verification

Run `./gradlew :distribution:contract:check`.
