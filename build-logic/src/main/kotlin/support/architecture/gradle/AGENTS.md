# Architecture Gradle adapters

This directory owns the Gradle task boundary for architecture projection and verification.

- Extract raw Gradle inputs only in cacheable task actions, then pass typed policy and projection
  values to the pure architecture owners.
- Emit fixed reports through dedicated `@Serializable` documents and explicit generated
  `.serializer()` factories. Keep finding attributes as the only open-key report field.
- Do not duplicate module-role, dependency, effect, or retired-surface policy in task code.
- `verifyKastVfsPassiveFirewallNegative` re-derives all fixed forbidden-authority cases;
  `verifyKastVfsPassiveFirewall` emits `build/reports/delivery/KVP-009-firewall.json` through one
  generated serializer document.
- `HostedReadClassInputs.kt` reads each compiled hosted class once. `HostedReadProjectInputs.kt`
  binds each resolved runtime project path to one artifact snapshot. `HostedReadExternalInputs.kt`
  binds each resolved external runtime coordinate to one artifact snapshot. KVP-018 uses those
  bytes for digest and ASM proof, admits both exact artifact sets, and writes
  `workspace/intellij-read/build/reports/KVP-018-no-walk.json`.
- `verifyNoDefaultRuntimeFallbackNegative` owns the synthetic fallback-linked misuse;
  `verifyNoDefaultRuntimeFallback` reads compiled CLI classes once and writes the closed
  `cli/build/reports/KVP-027-no-fallback.json` projection.

Run `./gradlew -p build-logic test`, then `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
