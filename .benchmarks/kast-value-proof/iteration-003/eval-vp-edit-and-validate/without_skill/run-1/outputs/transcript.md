# eval-vp-edit-and-validate without_skill run-1
Start: 2026-05-07T02:45:46Z

Prompt: Add a @Deprecated annotation with message 'Use FeatureRegistry instead' to the NamespaceRegistry interface declaration. Confirm the file still compiles after the edit.

## Reset confirmation
Command: `git reset --hard f191bc264fb18b65d54f228233d7630589fbaf37 && git clean -fd && git status --short`
```
(clean)
```

## Discovery
Command: `find . -name NamespaceRegistry.kt -print`
```
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt
```

Command: `grep -R -n --exclude-dir=.git "interface NamespaceRegistry\|class NamespaceRegistry" .`
```
./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt:16:interface NamespaceRegistry {
```

## File before edit: ./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt
```kotlin
@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.registry

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.ops.RegistryHooks

/**
 * Abstraction for managing feature flag configurations and evaluation state.
 */
interface NamespaceRegistry {
    val namespaceId: String

    val configuration: Configuration

    val hooks: RegistryHooks

    fun setHooks(hooks: RegistryHooks)

    val isAllDisabled: Boolean

    fun load(config: Configuration)

    val history: List<NamespaceSnapshot>

    fun rollback(steps: Int = 1): Boolean

    fun disableAll()

    fun enableAll()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any, C : Context, M : Namespace> flag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M> =
        configuration.flags[key] as FlagDefinition<T, C, M>

    /**
     * Safe lookup variant for callers that prefer typed absence handling over exceptions.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any, C : Context, M : Namespace> findFlag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M>? =
        configuration.flags[key] as? FlagDefinition<T, C, M>

    fun allFlags(): Map<Feature<*, *, *>, FlagDefinition<*, *, *>> =
        configuration.flags
}
```

## Applied edit
Inserted `@Deprecated("Use FeatureRegistry instead")` immediately above the `NamespaceRegistry` declaration.

## File after edit: ./konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt
```kotlin
@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.registry

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.ops.RegistryHooks

/**
 * Abstraction for managing feature flag configurations and evaluation state.
 */
@Deprecated("Use FeatureRegistry instead")
interface NamespaceRegistry {
    val namespaceId: String

    val configuration: Configuration

    val hooks: RegistryHooks

    fun setHooks(hooks: RegistryHooks)

    val isAllDisabled: Boolean

    fun load(config: Configuration)

    val history: List<NamespaceSnapshot>

    fun rollback(steps: Int = 1): Boolean

    fun disableAll()

    fun enableAll()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any, C : Context, M : Namespace> flag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M> =
        configuration.flags[key] as FlagDefinition<T, C, M>

    /**
     * Safe lookup variant for callers that prefer typed absence handling over exceptions.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any, C : Context, M : Namespace> findFlag(
        key: Feature<T, C, M>,
    ): FlagDefinition<T, C, M>? =
        configuration.flags[key] as? FlagDefinition<T, C, M>

    fun allFlags(): Map<Feature<*, *, *>, FlagDefinition<*, *, *>> =
        configuration.flags
}
```

## Compile confirmation
Command: `./gradlew compileKotlin --offline 2>&1 | tail -50`
Exit code: 0
Last 50 lines:
```
Reusing configuration cache.
> Task :smoke-test:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :konditional-json:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :konditional-types:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :konditional-engine:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :smoke-test:compileKotlin NO-SOURCE
> Task :konditional-types:compileKotlin UP-TO-DATE
> Task :konditional-types:compileJava NO-SOURCE

> Task :konditional-engine:compileKotlin
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:10:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:46:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:63:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:69:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:81:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:92:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt:130:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:6:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:12:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluationMetrics.kt:26:15 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:9:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:104:19 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:147:19 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:17:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:31:19 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:33:5 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:258:19 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:11:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/dsl/rules/RuleValueScope.kt:24:37 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/InMemoryNamespaceRegistry.kt:32:5 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:8:8 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:37:19 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:50:23 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:62:23 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.
w: file:///Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt:74:23 'interface NamespaceRegistry : Any' is deprecated. Use FeatureRegistry instead.

> Task :konditional-engine:compileJava NO-SOURCE
> Task :konditional-json:compileKotlin

BUILD SUCCESSFUL in 2s
3 actionable tasks: 2 executed, 1 up-to-date
Configuration cache entry reused.
```
End: 2026-05-07T02:45:50Z
