# Architecture Gradle adapters

This directory owns the Gradle task boundary for architecture projection and verification.

- Extract raw Gradle inputs only in cacheable task actions, then pass typed policy and projection
  values to the pure architecture owners.
- Emit fixed reports through dedicated `@Serializable` documents and explicit generated
  `.serializer()` factories. Keep finding attributes as the only open-key report field.
- Do not duplicate module-role, dependency, effect, or retired-surface policy in task code.

Run `./gradlew -p build-logic test`, then `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
