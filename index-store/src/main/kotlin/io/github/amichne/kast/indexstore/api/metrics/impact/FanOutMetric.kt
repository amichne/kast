package io.github.amichne.kast.indexstore.api.metrics.impact

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import kotlinx.serialization.Serializable

@Serializable
data class FanOutMetric(
    val sourcePath: String,
    val sourceModulePath: String?,
    val sourceSourceSet: String?,
    val occurrenceCount: Int,
    val targetSymbolCount: Int,
    val targetFileCount: Int,
    val targetModuleCount: Int,
    val externalTargetCount: Int,
    val byEdgeKind: Map<String, Int>,
    val confidence: Confidence,
)
