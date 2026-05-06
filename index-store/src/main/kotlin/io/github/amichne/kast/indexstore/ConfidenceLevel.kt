package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    SPECULATIVE,
}
