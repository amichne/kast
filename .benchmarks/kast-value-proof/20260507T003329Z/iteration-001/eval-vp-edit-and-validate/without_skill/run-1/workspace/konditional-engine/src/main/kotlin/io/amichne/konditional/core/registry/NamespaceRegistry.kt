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
