package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class ApiSurfaceMetric(
    val modulePath: String,
    val publicSymbolCount: Int,
    val internalSymbolCount: Int,
    val privateSymbolCount: Int,
    val totalSymbolCount: Int,
    val encapsulationRatio: Double,
)
