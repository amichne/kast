package io.github.amichne.kast.indexstore.api.metrics.general

import kotlinx.serialization.Serializable

@Serializable
data class Confidence(
    val level: ConfidenceLevel,
    val indexCompleteness: Double,
    val semanticBasis: SemanticBasis,
)
