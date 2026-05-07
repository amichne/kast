@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.core.schema

import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.features.Feature
import io.amichne.konditional.values.FeatureId

/**
 * Immutable schema-plane view derived from compile-time namespace declarations.
 *
 * This schema defines the trusted feature universe for boundary materialization.
 */
@ConsistentCopyVisibility
data class CompiledNamespaceSchema internal constructor(
    val namespaceId: String,
    val entriesById: Map<FeatureId, Entry>,
) {
    val entriesInDeterministicOrder: List<Entry>
        get() = entriesById.values.sortedBy { it.featureId.toString() }

    @KonditionalInternalApi
    data class Entry(
        val featureId: FeatureId,
        val feature: Feature<*, *, *>,
        val declaredDefinition: FlagDefinition<*, *, *>,
    )

    companion object {
        @KonditionalInternalApi
        fun from(namespace: Namespace): CompiledNamespaceSchema {
            val entries =
                namespace
                    .allFeatures()
                    .sortedBy { feature -> feature.id.toString() }
                    .associate { feature ->
                        feature.id to Entry(
                            featureId = feature.id,
                            feature = feature,
                            declaredDefinition = namespace.requireDeclaredDefinition(feature),
                        )
                    }
            return CompiledNamespaceSchema(namespaceId = namespace.id.value, entriesById = entries)
        }
    }
}

private fun Namespace.requireDeclaredDefinition(
    feature: Feature<*, *, *>,
): FlagDefinition<*, *, *> =
    declaredDefinition(feature)
        ?: error("Declared definition missing for feature '${feature.id}' in namespace '$id'")
