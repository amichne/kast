package io.github.amichne.kast.indexstore.api.metrics.impact

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import kotlinx.serialization.Serializable

@Serializable
data class FanInMetric(
    val targetFqName: String,
    val targetPath: String?,
    val targetModulePath: String?,
    val targetSourceSet: String?,
    val occurrenceCount: Int,
    val sourceFileCount: Int,
    val sourceModuleCount: Int,
    val byEdgeKind: Map<String, Int>,
    val confidence: Confidence,
)
