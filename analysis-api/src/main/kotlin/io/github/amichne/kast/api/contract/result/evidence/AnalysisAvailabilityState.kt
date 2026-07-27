package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class AnalysisAvailabilityState {
    AVAILABLE,
    PENDING,
    FAILED,
    NOT_APPLICABLE,
}
