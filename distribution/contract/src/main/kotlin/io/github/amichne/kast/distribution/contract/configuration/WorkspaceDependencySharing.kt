package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

/** Creation-time dependency paths; an existing workspace always retains its recorded Gradle home. */
@Serializable
enum class WorkspaceDependencySharing {
    PRIVATE,
    SHARED;

    companion object {
        val DEFAULT = SHARED

        fun parseSetting(raw: String): Refinement<WorkspaceDependencySharing, ConfigurationFailure> =
            when (raw) {
                "PRIVATE" -> Refinement.Refined(PRIVATE)
                "SHARED" -> Refinement.Refined(SHARED)
                else -> Refinement.Rejected(ConfigurationFailure.INVALID_VALUE)
            }
    }
}
