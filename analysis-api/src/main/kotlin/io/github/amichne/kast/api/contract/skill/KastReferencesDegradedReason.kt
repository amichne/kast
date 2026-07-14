package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
enum class KastReferencesDegradedReason {
    REFERENCES_UNAVAILABLE,
    INDEX_IDENTITY_UNAVAILABLE,
    BOUND_SOURCE_UNAVAILABLE,
    CANDIDATE_BUDGET_REACHED,
}
