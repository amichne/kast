package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
enum class KastHierarchyDegradedReason {
    TYPE_HIERARCHY_UNAVAILABLE,
    CANDIDATE_BUDGET_REACHED,
    TRAVERSAL_STATE_BUDGET_REACHED,
    TIMEOUT,
    CANCELLED,
}
