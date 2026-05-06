package io.github.amichne.kast.indexstore.graph

import io.github.amichne.kast.indexstore.api.graph.MetricsGraphNode

internal data class Node(
    val id: String,
    val name: String,
    val type: NodeType,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val attributes: List<String> = emptyList(),
)

internal fun Node.toApi(): MetricsGraphNode =
    MetricsGraphNode(
        id = id,
        name = name,
        type = type.toApi(),
        parentId = parentId,
        children = children,
        attributes = attributes,
    )
