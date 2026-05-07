Added this annotation in the disposable workspace:

- File: `konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt`
- Resulting declaration line: `@Deprecated("Use FeatureRegistry instead")` at line 16

Disposable workspace used:
- `/Users/amichne/code/kast/.benchmarks/kast-value-proof/20260507T003329Z/iteration-001/eval-vp-edit-and-validate/without_skill/run-1/workspace`

Validation:
- `JAVA_HOME=/Users/amichne/.sdkman/candidates/java/21.0.8-graal ./gradlew --offline :konditional-engine:compileKotlin`
- Result: **BUILD SUCCESSFUL**

The file still compiles after the edit. The build produced deprecation warnings at existing use sites, which is expected, but there were no compile errors.
