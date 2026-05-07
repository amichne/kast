# eval-vp-multi-file-rename without_skill run-1
Start: 2026-05-07T02:45:37Z

Prompt: Rename NamespaceRegistry to FeatureRegistry across the entire workspace. Show me the edit plan before applying. After applying, confirm no compile errors were introduced.

## Discovery
Command: `grep -R -n --exclude-dir=.git "NamespaceRegistry" .`
```
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:9:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:23:        val registry = InMemoryNamespaceRegistry(namespaceId = "atomic-registry")
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:44:        val registry = InMemoryNamespaceRegistry(namespaceId = "linearizable")
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:106:        registry: InMemoryNamespaceRegistry,
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:12:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:35:        val registry = namespace.registry as InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:103:        val registry = namespace.registry as InMemoryNamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:16:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:17:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:31:    val registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id.value),
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:33:) : NamespaceRegistry by registry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:258:        registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id),
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:9:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:104:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:147:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt:16:interface NamespaceRegistry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceSnapshot.kt:10: * [NamespaceSnapshot] is the unit of atomic exchange in [InMemoryNamespaceRegistry].
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:12: * In-memory [NamespaceRegistry] implementation.
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:28:class InMemoryNamespaceRegistry(
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:32:) : NamespaceRegistry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/AGENTS.md:9:- `InMemoryNamespaceRegistry.kt`
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/AGENTS.md:10:- `NamespaceRegistry.kt`
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:11:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:24:    private val evaluationRegistry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:8:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:37:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:50:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:62:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:74:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:10:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:46:    registry: NamespaceRegistry = namespace,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:63:    registry: NamespaceRegistry = namespace,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:69:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:81:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:92:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:130:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:6:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:12:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:26:    registry: NamespaceRegistry,
./docs/guarantees/atomic-snapshots.md:27:`InMemoryNamespaceRegistry` uses an `AtomicReference` for the current snapshot
./docs/guarantees/atomic-snapshots.md:31:- `InMemoryNamespaceRegistry`
./docs/guarantees/namespace-isolation.md:31:- `NamespaceRegistry`
./docs/concepts/runtime-snapshots.md:9:such as version, source, and generation time. `NamespaceRegistry` is the
./docs/concepts/runtime-snapshots.md:12:The default runtime implementation is `InMemoryNamespaceRegistry`.
./docs/concepts/runtime-snapshots.md:28:`InMemoryNamespaceRegistry` stores the current snapshot behind an
./docs/reference/api-surface.md:42:| `NamespaceRegistry` | Load, rollback, disable, enable, and flag lookup contract |
./docs/reference/api-surface.md:43:| `InMemoryNamespaceRegistry` | Default in-memory registry with atomic snapshot replacement |
```

## Edit plan (shown before applying)
Replace every textual occurrence of `NamespaceRegistry` with `FeatureRegistry` in the files listed below, preserving line content except for the identifier rename. Rename any file named `NamespaceRegistry.kt` to `FeatureRegistry.kt`.

```
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:9:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:23:        val registry = InMemoryNamespaceRegistry(namespaceId = "atomic-registry")
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:44:        val registry = InMemoryNamespaceRegistry(namespaceId = "linearizable")
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt:106:        registry: InMemoryNamespaceRegistry,
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:12:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:35:        val registry = namespace.registry as InMemoryNamespaceRegistry
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt:103:        val registry = namespace.registry as InMemoryNamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:16:import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:17:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:31:    val registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id.value),
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:33:) : NamespaceRegistry by registry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:258:        registry: NamespaceRegistry = InMemoryNamespaceRegistry(namespaceId = id),
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:9:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:104:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:147:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt:16:interface NamespaceRegistry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceSnapshot.kt:10: * [NamespaceSnapshot] is the unit of atomic exchange in [InMemoryNamespaceRegistry].
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:12: * In-memory [NamespaceRegistry] implementation.
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:28:class InMemoryNamespaceRegistry(
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:32:) : NamespaceRegistry {
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/AGENTS.md:9:- `InMemoryNamespaceRegistry.kt`
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/AGENTS.md:10:- `NamespaceRegistry.kt`
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:11:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:24:    private val evaluationRegistry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:8:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:37:        registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:50:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:62:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:74:            registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:10:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:46:    registry: NamespaceRegistry = namespace,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:63:    registry: NamespaceRegistry = namespace,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:69:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:81:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:92:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:130:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:6:import io.amichne.konditional.core.registry.NamespaceRegistry
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:12:    registry: NamespaceRegistry,
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:26:    registry: NamespaceRegistry,
./docs/guarantees/atomic-snapshots.md:27:`InMemoryNamespaceRegistry` uses an `AtomicReference` for the current snapshot
./docs/guarantees/atomic-snapshots.md:31:- `InMemoryNamespaceRegistry`
./docs/guarantees/namespace-isolation.md:31:- `NamespaceRegistry`
./docs/concepts/runtime-snapshots.md:9:such as version, source, and generation time. `NamespaceRegistry` is the
./docs/concepts/runtime-snapshots.md:12:The default runtime implementation is `InMemoryNamespaceRegistry`.
./docs/concepts/runtime-snapshots.md:28:`InMemoryNamespaceRegistry` stores the current snapshot behind an
./docs/reference/api-surface.md:42:| `NamespaceRegistry` | Load, rollback, disable, enable, and flag lookup contract |
./docs/reference/api-surface.md:43:| `InMemoryNamespaceRegistry` | Default in-memory registry with atomic snapshot replacement |
```

## Applied edits
Files with replacements:
```
./konditional-engine/src/test/kotlin/io/amichne/konditional/runtime/NamespaceAtomicityTest.kt
./konditional-engine/src/test/kotlin/io/amichne/konditional/engine/NamespaceRuntimeTest.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceSnapshot.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/AGENTS.md
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt
./konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt
./docs/guarantees/atomic-snapshots.md
./docs/guarantees/namespace-isolation.md
./docs/concepts/runtime-snapshots.md
./docs/reference/api-surface.md
```
Files renamed:
```
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt
```

Remaining `NamespaceRegistry` occurrences after edit:
```
0
```

## Compile confirmation
Command: `./gradlew compileKotlin --offline 2>&1 | tail -50`
Exit code: 0
Last 50 lines:
```
Calculating task graph as no cached configuration is available for tasks: compileKotlin
> Task :build-logic:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :build-logic:generateExternalPluginSpecBuilders FROM-CACHE
> Task :build-logic:extractPrecompiledScriptPluginPlugins FROM-CACHE
> Task :build-logic:compilePluginsBlocks FROM-CACHE
> Task :build-logic:generatePrecompiledScriptPluginAccessors FROM-CACHE
> Task :build-logic:generateScriptPluginAdapters FROM-CACHE
> Task :build-logic:compileKotlin FROM-CACHE
> Task :build-logic:compileJava NO-SOURCE
> Task :build-logic:pluginDescriptors
> Task :build-logic:processResources
> Task :build-logic:classes
> Task :build-logic:jar
> Task :konditional-json:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :smoke-test:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :konditional-types:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :konditional-engine:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :smoke-test:compileKotlin NO-SOURCE

> Task :konditional-types:compileKotlin
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-types/src/main/kotlin/io/amichne/konditional/context/axis/Axes.kt:86:22 'fun <T : Any!> toArray(p0: IntFunction<Array<(out) T!>!>!): Array<(out) T!>!' is deprecated. This declaration is redundant in Kotlin and might be removed soon.

> Task :konditional-types:compileJava NO-SOURCE
> Task :konditional-engine:compileKotlin
> Task :konditional-engine:compileJava NO-SOURCE
> Task :konditional-json:compileKotlin

BUILD SUCCESSFUL in 7s
12 actionable tasks: 6 executed, 6 from cache
Configuration cache entry stored.
```
End: 2026-05-07T02:45:46Z
