package io.github.amichne.kast.topology.contract.historical

import java.util.Collections

enum class HistoricalFactFamily {
    MODULES,
    FILES,
    DECLARATIONS,
    REFERENCES,
}

enum class HistoricalLimitation {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_TARGET,
    EXTERNAL_TARGET,
    UNSUPPORTED_TARGET,
    OWNER_UNAVAILABLE,
    MODEL_INCOMPLETE,
    ROOTLESS,
    GENERATED_INPUT,
    RESOURCE_LIMIT,
}

sealed interface HistoricalCoverageStatus {
    data object Complete : HistoricalCoverageStatus

    data object NotCollected : HistoricalCoverageStatus

    data class OutsideProfile(val reason: HistoricalLimitation) : HistoricalCoverageStatus

    /** A qualification always carries at least one finite cause. */
    class Qualified(first: HistoricalLimitation, rest: Set<HistoricalLimitation> = emptySet()) :
        HistoricalCoverageStatus {
        val reasons: Set<HistoricalLimitation> = Collections.unmodifiableSet((rest + first).toSortedSet())

        override fun equals(other: Any?): Boolean = other is Qualified && reasons == other.reasons

        override fun hashCode(): Int = reasons.hashCode()
    }
}

data class HistoricalCoverage(
    val module: HistoricalModuleId,
    val family: HistoricalFactFamily,
    val status: HistoricalCoverageStatus,
)
