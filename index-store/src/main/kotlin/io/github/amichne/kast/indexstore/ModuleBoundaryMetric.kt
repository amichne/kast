package io.github.amichne.kast.indexstore

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
