package io.github.amichne.kast.cli

import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsGraphNode

internal object MetricsGraphShell {
    fun render(graph: MetricsGraph): String {
        val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)
        val focal = nodesById.getValue(graph.focalNodeId)
        return buildString {
            appendLine("Kast graph visualizer")
            appendLine("Keys: U parent · D/Enter first child · ←/→ sibling · A attributes/members")
            appendLine("Index: ${graph.index.symbolCount} symbols · ${graph.index.fileCount} files · ${graph.index.referenceCount} refs · depth ${graph.index.maxDepth}")
            appendLine()
            append(renderNode(graph = graph, current = focal, showAttributes = true))
        }
    }

    private fun renderNode(
        graph: MetricsGraph,
        current: MetricsGraphNode,
        showAttributes: Boolean,
    ): String {
        val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)
        val parent = current.parentId?.let(nodesById::get)
        val children = current.children.mapNotNull(nodesById::get)
        val siblings = siblingNodes(graph = graph, current = current)
        return buildString {
            appendLine("▶ ${current.name}")
            appendLine("  type: ${current.type}")
            appendLine("  node: ${current.id}")
            appendLine("  parent: ${parent?.name ?: "∅"}")
            appendLine("  siblings: ${siblings.joinToString { it.name }.ifBlank { "∅" }}")
            appendLine("  children:")
            if (children.isEmpty()) {
                appendLine("    ∅")
            } else {
                children.forEachIndexed { index, child ->
                    appendLine("    ${index + 1}. ${child.name} [${child.type}]")
                }
            }
            if (showAttributes) {
                appendLine("  attributes:")
                if (current.attributes.isEmpty()) {
                    appendLine("    ∅")
                } else {
                    current.attributes.forEach { attribute ->
                        appendLine("    - $attribute")
                    }
                }
            }
            appendLine()
            appendLine("Graph edges from here:")
            graph.edges
                .filter { edge -> edge.from == current.id || edge.to == current.id }
                .forEach { edge ->
                    appendLine("  ${edge.from} -${edge.edgeType}/${edge.weight}-> ${edge.to}")
                }
        }
    }

    private fun siblingNodes(
        graph: MetricsGraph,
        current: MetricsGraphNode,
    ): List<MetricsGraphNode> {
        val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)
        val parentId = current.parentId ?: return emptyList()
        val parent = nodesById[parentId] ?: return emptyList()
        return parent.children
            .filterNot { it == current.id }
            .mapNotNull(nodesById::get)
    }
}
