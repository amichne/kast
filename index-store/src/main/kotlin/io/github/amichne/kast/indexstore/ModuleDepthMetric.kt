package io.github.amichne.kast.indexstore

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
