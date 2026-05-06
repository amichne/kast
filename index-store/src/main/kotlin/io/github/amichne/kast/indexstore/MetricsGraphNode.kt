package io.github.amichne.kast.indexstore

import kotlinx.serialization.Serializable

@Serializable
data class MetricsGraphNode(
    val id: String,
    val name: String,
    val type: MetricsGraphNodeType,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val attributes: List<String> = emptyList(),
)
