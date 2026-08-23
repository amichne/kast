package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.OperationId

/** Typed generated-resource projection of one already proven canonical operation registry. */
class OperationRegistryArtifact private constructor(
    val operationIds: List<OperationId>,
) {
    companion object {
        /**
         * Proof transition: `OperationRegistry -> OperationRegistryArtifact`.
         *
         * Preserves the registry's exact, unique canonical ordering as typed operation identities.
         * Raw transport encoding is permitted only in `:protocol:wire`.
         */
        fun from(registry: OperationRegistry): OperationRegistryArtifact =
            OperationRegistryArtifact(registry.definitions.map { it.id })
    }
}
