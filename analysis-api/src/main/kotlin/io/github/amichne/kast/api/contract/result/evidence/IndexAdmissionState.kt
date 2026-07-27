package io.github.amichne.kast.api.contract.result

import kotlinx.serialization.Serializable

@Serializable
enum class IndexAdmissionState {
    ADMITTED,
    PENDING,
    NOT_APPLICABLE,
}
