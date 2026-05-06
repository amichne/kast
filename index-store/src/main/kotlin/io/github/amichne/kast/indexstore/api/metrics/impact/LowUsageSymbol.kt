package io.github.amichne.kast.indexstore.api.metrics.impact

import io.github.amichne.kast.indexstore.api.metrics.general.Confidence
import kotlinx.serialization.Serializable

@Serializable
data class LowUsageSymbol(
    val targetFqName: String,
    val targetPath: String?,
    val targetModulePath: String?,
    val occurrenceCount: Int,
    val sourceFileCount: Int,
    val confidence: Confidence,
)
