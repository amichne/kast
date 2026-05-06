package io.github.amichne.kast.indexstore.api.metrics.general

import kotlinx.serialization.Serializable

@Serializable
enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    SPECULATIVE,
}
