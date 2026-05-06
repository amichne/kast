package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class DeadCodeCandidate(
    val fqName: String,
    val kind: String,
    val visibility: String,
    val path: String?,
    val modulePath: String?,
    val sourceSet: String?,
    val confidence: Confidence,
    val reason: String,
)
