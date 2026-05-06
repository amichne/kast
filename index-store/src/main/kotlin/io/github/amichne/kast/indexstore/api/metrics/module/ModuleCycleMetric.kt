package io.github.amichne.kast.indexstore.api.metrics.module

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
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
