package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class ModuleCycleMetric(
    val cycle: List<String>,
    val totalReferenceCount: Int,
    val weakestEdgeSource: String,
    val weakestEdgeTarget: String,
    val weakestEdgeReferenceCount: Int,
    val confidence: Confidence,
)
