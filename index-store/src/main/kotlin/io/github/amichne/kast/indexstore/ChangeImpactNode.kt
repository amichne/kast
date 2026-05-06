package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class ChangeImpactNode(
    val sourcePath: String,
    val depth: Int,
    val viaTargetFqName: String,
    val edgeKind: String?,
    val occurrenceCount: Int,
    val confidence: Confidence,
)
