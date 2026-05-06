package io.github.amichne.kast.indexstore.api.metrics.module

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import kotlinx.serialization.Serializable

@Serializable
data class ModuleBoundaryMetric(
    val modulePath: String,
    val exportedSymbolCount: Int,
    val consumedSymbolCount: Int,
    val publicApiReferences: Int,
    val internalLeakReferences: Int,
    val confidence: Confidence,
)
