package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException

internal class BackendCapabilityGate(
    private val backend: AnalysisBackend,
) {
    suspend fun requireRead(capability: ReadCapability) {
        requireCapabilities(readCapabilities = setOf(capability))
    }

    suspend fun requireMutation(capability: MutationCapability) {
        requireCapabilities(mutationCapabilities = setOf(capability))
    }

    suspend fun requireCapabilities(
        readCapabilities: Set<ReadCapability> = emptySet(),
        mutationCapabilities: Set<MutationCapability> = emptySet(),
    ) {
        val capabilities = backend.capabilities()
        val missingReadCapability = readCapabilities.firstOrNull { capability ->
            capability !in capabilities.readCapabilities
        }
        if (missingReadCapability != null) {
            throw CapabilityNotSupportedException(
                capability = missingReadCapability.name,
                message = "The backend does not advertise $missingReadCapability",
            )
        }
        val missingMutationCapability = mutationCapabilities.firstOrNull { capability ->
            capability !in capabilities.mutationCapabilities
        }
        if (missingMutationCapability != null) {
            throw CapabilityNotSupportedException(
                capability = missingMutationCapability.name,
                message = "The backend does not advertise $missingMutationCapability",
            )
        }
    }
}
