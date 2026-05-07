Edit plan:
1. Rename `konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt` to `FeatureRegistry.kt`.
2. Update exact `NamespaceRegistry` symbol references in these files:
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt`
   - `konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt`
3. Do **not** rename `InMemoryNamespaceRegistry`; it is a different symbol.

Applied in disposable workspace:
- `/Users/amichne/code/kast/.benchmarks/kast-value-proof/20260507T003329Z/iteration-001/eval-vp-multi-file-rename/without_skill/run-1/workspace`

Validation:
- `JAVA_HOME=/Users/amichne/.sdkman/candidates/java/21.0.8-graal ./gradlew --offline compileKotlin`
- Result: **BUILD SUCCESSFUL**

So the rename to `FeatureRegistry` was applied in the disposable copy and introduced no compile errors.
