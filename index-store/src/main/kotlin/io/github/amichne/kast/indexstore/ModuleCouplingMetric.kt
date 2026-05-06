package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class ModuleCouplingMetric(
    val sourceModulePath: String,
    val sourceSourceSet: String?,
    val targetModulePath: String,
    val targetSourceSet: String?,
    val referenceCount: Int,
    val publicApiCount: Int,
    val internalLeakCount: Int,
    val byEdgeKind: Map<String, Int>,
    val confidence: Confidence,
)
