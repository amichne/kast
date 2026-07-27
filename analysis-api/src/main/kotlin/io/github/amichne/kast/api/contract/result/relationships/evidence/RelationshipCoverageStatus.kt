package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class RelationshipCoverageStatus {
    COMPLETE,
    IN_PROGRESS,
    PARTIAL,
    STALE,
    EXCLUDED,
    TIMED_OUT,
    CANCELLED,
    UNAVAILABLE,
}
