# Standalone Kast IDE plugin module

`:ide-plugin` owns the independently installable IntelliJ plugin ZIP.

## Boundary

- Apply the `IDE_READ_ONLY` role and declare no dependency on the isolated indexer runtime.
- Depend inward on `:protocol:contract` for the host-neutral compatibility model; keep that model
  behind module-internal metadata adapters so the plugin does not export the contract dependency.
- The module JAR owns delivery of `META-INF/plugin.xml` from the canonical indexer registration
  source; the staged legacy Kast payload is a build input only while later delivery tasks refine
  runtime and workspace implementations into read-only modules.
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

Run `./gradlew :ide-plugin:standalonePluginNegativeProof :ide-plugin:buildPlugin
:ide-plugin:verifyPluginLayoutNegative`, then the architecture gates and
`:indexer:verifyPortableDistLayout`. Run `:ide-plugin:verifyPluginLayout` only after the final
read-only payload replaces the transitional archive. Run
`./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'` for KVP-012.
