# Standalone Kast IDE plugin module

`:ide-plugin` owns the independently installable IntelliJ plugin ZIP.

## Boundary

- Apply the `IDE_READ_ONLY` role and declare no dependency on the isolated indexer runtime.
- The module JAR owns delivery of `META-INF/plugin.xml` from the canonical indexer registration
  source; the staged legacy Kast payload is a build input only while later delivery tasks refine
  runtime and workspace implementations into read-only modules.
- `buildPlugin` is the KVP-010 artifact and report authority. It consumes private non-platform JARs,
  emits one deterministic ZIP under `build/distributions`, and writes
  `build/reports/KVP-010-plugin.json`.
- Never copy an IDEA home or bundle platform-owned IntelliJ, Kotlin-plugin, Gradle-plugin, or JBR
  classes into this artifact.

Run `./gradlew :ide-plugin:standalonePluginNegativeProof :ide-plugin:buildPlugin`, then the
architecture gates and `:indexer:verifyPortableDistLayout`.
