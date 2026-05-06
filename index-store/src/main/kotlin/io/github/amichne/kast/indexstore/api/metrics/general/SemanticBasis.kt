package io.github.amichne.kast.indexstore.api.metrics.general

import kotlinx.serialization.Serializable

@Serializable
enum class SemanticBasis {
    K2_RESOLVED,
    LEXICAL,
    HEURISTIC,
}
