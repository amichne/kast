package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.OperationId

data class OperationRegistryArtifactEntry(
    val operationId: OperationId,
    val hostedExposure: HostedExposure,
    val hostedIntentIds: List<String>,
)

/** Typed generated-resource projection of one already proven canonical operation registry. */
class OperationRegistryArtifact private constructor(
    val entries: List<OperationRegistryArtifactEntry>,
) {
    val operationIds: List<OperationId>
        get() = entries.map { it.operationId }

    companion object {
        /**
         * Proof transition: `OperationRegistry -> OperationRegistryArtifact`.
         *
         * Preserves the registry's exact, unique canonical ordering as typed operation identities.
         * Raw transport encoding is permitted only in `:protocol:wire`.
         */
        fun from(registry: OperationRegistry): OperationRegistryArtifact =
            OperationRegistryArtifact(
                registry.definitions.map { definition ->
                    OperationRegistryArtifactEntry(
                        operationId = definition.id,
                        hostedExposure = definition.hostedExposure,
                        hostedIntentIds = when (val variants = definition.hostedVariants) {
                            is HostedVariants.Intents -> variants.intents
                                .sortedBy(HostedChangeIntent::ordinal)
                                .map(HostedChangeIntent::identity)
                            HostedVariants.None -> emptyList()
                        },
                    )
                },
            )
    }
}
