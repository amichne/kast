package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RuntimeCompatibilityOutcome {
    @Serializable
    @SerialName("COMPATIBLE")
    data class Compatible(
        @DocField(description = "Compatibility facts accepted by an explicit matrix row.")
        val facts: RuntimeCompatibilityFacts,
    ) : RuntimeCompatibilityOutcome

    @Serializable
    @SerialName("UPDATE_REQUIRED")
    data class UpdateRequired(
        @DocField(description = "Typed reason that a supported update is required.")
        val requirement: RuntimeCompatibilityUpdateRequirement,
    ) : RuntimeCompatibilityOutcome

    @Serializable
    @SerialName("MISSING_CAPABILITY")
    data class MissingCapability(
        @DocField(description = "Optional operation unavailable from the compatible runtime.")
        val capability: RuntimeCapability,
    ) : RuntimeCompatibilityOutcome
}
