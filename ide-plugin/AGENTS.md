# Standalone Kast IDE plugin module

`:ide-plugin` owns the independently installable IntelliJ plugin ZIP.

## Boundary

- Apply the `IDE_READ_ONLY` role and declare no dependency on the isolated indexer runtime.
- Depend inward on `:protocol:contract`, `:protocol:wire`, `:runtime:ide-read`,
  `:workspace:contract`, and `:workspace:intellij-read` for the hosted endpoint. Keep them behind
  module-internal adapters so the plugin does not export them. Do not depend on
  `:runtime:composition` or any isolated-indexer implementation.
- The module JAR owns the local `META-INF/plugin.xml`; it adds one preloaded project-scoped
  `IdeEndpointService` and one post-startup activity that supplies the already-open Project. The
  staged legacy starter/resolver registrations and payload remain build inputs only until later
  delivery tasks remove the transitional isolated-runtime path.
- `buildPlugin` is the KVP-010 artifact and report authority. It consumes private non-platform JARs,
  emits one deterministic ZIP under `build/distributions`, and writes
  `build/reports/KVP-010-plugin.json`.
- `verifyPluginLayoutNegative` is the executable KVP-011 rejection-sensitivity gate.
  `verifyPluginLayout` scans the physical ZIP and every nested class owner and bytecode effect; it
  must reject the transitional KVP-010 payload until KVP-025 and KVP-031 provide the final hosted
  service and exact four-operation runtime.
- `generateIdeHostCompatibilityReport` is the KVP-012 report authority. It binds separate hosted
  IDEA and bundled Kotlin-plugin pins, `project.version`, the IDE-hosted runtime protocol, the
  physical operation-registry digest, the canonical wire-schema digest, and the exact four
  `CanonicalOperation` capabilities without a Boolean compatibility flag.
- Never copy an IDEA home or bundle platform-owned IntelliJ, Kotlin-plugin, Gradle-plugin, or JBR
  classes into this artifact.
- `endpoint/` owns KVP-024's exact-root UDS bind and atomic descriptor-v2 publication. It consumes
  only the complete hosted runtime capability, serves bounded framed sessions, and never opens,
  imports, refreshes, or falls back. The two hosted project JARs remain explicit transitional
  payload inputs until KVP-011 proves the final classpath closure.

Run `./gradlew :ide-plugin:standalonePluginNegativeProof :ide-plugin:buildPlugin
:ide-plugin:verifyPluginLayoutNegative`, then the architecture gates and
`:indexer:verifyPortableDistLayout`. Run `:ide-plugin:verifyPluginLayout` only after the final
read-only payload replaces the transitional archive. Run
`./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'` for KVP-012.
