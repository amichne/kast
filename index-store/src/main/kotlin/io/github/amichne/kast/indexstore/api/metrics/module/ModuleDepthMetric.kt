package io.github.amichne.kast.indexstore.api.metrics.module

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import kotlinx.serialization.Serializable

@Serializable
data class ModuleDepthMetric(
    val modulePath: String,
    val fileCount: Int,
    val declaredSymbolCount: Int,
    val internalRefCount: Int,
    val externalRefCount: Int,
    val cohesionRatio: Double,
    val refsPerFile: Double,
    val diagnosis: ModuleDepthDiagnosis,
    val confidence: Confidence,
)
