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
  isolated starter, resolver, and transitive indexer payload remain absent from this artifact.
- `buildPlugin` is the KVP-010 artifact and report authority. It consumes private non-platform JARs,
  emits one deterministic ZIP under `build/distributions`, and writes
  `build/reports/KVP-010-plugin.json`.
- `verifyPluginLayoutNegative` is the executable KVP-011 rejection-sensitivity gate.
  `verifyPluginLayout` scans the physical ZIP and every nested class owner and bytecode effect; it
  admits only the minimal hosted service and exact four-operation read classpath.
- `generateIdeHostCompatibilityReport` is the KVP-012 report authority. It binds separate hosted
  IDEA and bundled Kotlin-plugin pins, `project.version`, the IDE-hosted runtime protocol, the
  physical operation-registry digest, the canonical wire-schema digest, and the exact four
  `CanonicalOperation` capabilities without a Boolean compatibility flag.
- Never copy an IDEA home or bundle platform-owned IntelliJ, Kotlin-plugin, Gradle-plugin, or JBR
  classes into this artifact.
- `endpoint/` owns KVP-024's exact-root UDS bind and atomic descriptor-v2 publication. It consumes
  only the complete hosted runtime capability, serves bounded framed sessions, and never opens,
  imports, refreshes, or falls back. KVP-025 binds its descriptor/socket retirement to the Project
  service lifecycle and deletes only retained physical identities. Packaged dependencies resolve
  only from the hosted plugin's own runtime classpath.
- `proveKVP025Cases` is the internal graph-configured nine-case retirement suite. It emits bounded
  test evidence only when root `proveKVP025` determines that the content closure requires fresh
  execution; do not expose a second public KVP-025 proof path.

Run `./gradlew :ide-plugin:standalonePluginNegativeProof :ide-plugin:buildPlugin
:ide-plugin:verifyPluginLayoutNegative`, then the architecture gates and
`:indexer:verifyPortableDistLayout`. Run
`./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'` for KVP-012.
