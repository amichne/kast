package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RuntimeCapability {
    @Serializable
    @SerialName("READ")
    data class Read(
        @DocField(description = "Read operation advertised by the runtime.")
        val capability: ReadCapability,
    ) : RuntimeCapability

    @Serializable
    @SerialName("MUTATION")
    data class Mutation(
        @DocField(description = "Mutation operation advertised by the runtime.")
        val capability: MutationCapability,
    ) : RuntimeCapability
}
