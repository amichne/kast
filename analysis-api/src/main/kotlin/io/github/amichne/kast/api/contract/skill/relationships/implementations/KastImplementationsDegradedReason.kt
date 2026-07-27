package io.github.amichne.kast.api.contract.skill

import kotlinx.serialization.Serializable

@Serializable
enum class KastImplementationsDegradedReason {
    IMPLEMENTATIONS_UNAVAILABLE,
    CANDIDATE_BUDGET_REACHED,
    TRAVERSAL_STATE_BUDGET_REACHED,
    TIMEOUT,
    CANCELLED,
}
