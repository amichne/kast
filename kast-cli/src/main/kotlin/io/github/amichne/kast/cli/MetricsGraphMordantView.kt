package io.github.amichne.kast.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.widgets.Panel
import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsGraphEdge
import io.github.amichne.kast.indexstore.MetricsGraphEdgeType
import io.github.amichne.kast.indexstore.MetricsGraphNode
import io.github.amichne.kast.indexstore.MetricsGraphNodeType

internal object MetricsGraphMordantView {
    fun render(
        graph: MetricsGraph,
        cursor: MetricsGraphCursor,
    ): Widget {
        val context = MetricsGraphDashboardContext(graph, cursor)
        return verticalLayout {
            spacing = 1
            cell(headerPanel(graph))
            cell(section("Path", TextColors.brightBlue, context.pathBlock()))
            cell(section("Current", TextColors.brightGreen, context.currentBlock()))
            cell(section("Neighborhood", TextColors.brightCyan, context.neighborhoodBlock()))
            cell(section("Children", TextColors.brightYellow, context.childrenBlock()))
            cell(section("Relations", TextColors.brightMagenta, context.relationsBlock()))
        }
    }

    private fun headerPanel(graph: MetricsGraph): Widget {
        val statsLine = listOf(
            "${graph.index.symbolCount} symbols",
            "${graph.index.fileCount} files",
            "${graph.index.referenceCount} refs",
            "depth ${graph.index.maxDepth}",
        ).joinToString("  ") { TextColors.brightWhite(it) }

        val keys = sequenceOf(
            "U/↑" to "parent",
            "D/↓/Enter" to "child",
            "←/→" to "sibling",
            "A" to "members",
            "Q" to "quit",
        ).joinToString("  ") { (key, label) ->
            TextStyles.bold(TextColors.brightYellow(key)) + " " + TextColors.gray(label)
        }

        return Panel(
            content = statsLine + "\n" + keys,
            title = TextStyles.bold(TextColors.brightCyan("Kast graph visualizer")),
            expand = true,
            borderStyle = TextColors.brightCyan,
        )
    }

    private fun section(title: String, accent: TextStyle, body: String): Widget =
        Panel(
            content = body,
            title = TextStyles.bold(accent(title)),
            expand = true,
            borderStyle = accent,
        )
}

private class MetricsGraphDashboardContext(
    graph: MetricsGraph,
    cursor: MetricsGraphCursor,
) {
    private val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)
    private val current = nodesById.getValue(cursor.currentNodeId)
    private val parent = current.parentId?.let(nodesById::get)
    private val siblings = parent
        ?.children
        ?.mapNotNull(nodesById::get)
        .orEmpty()
    private val currentSiblingIndex = siblings.indexOfFirst { it.id == current.id }
    private val children = current.children.mapNotNull(nodesById::get)
    private val relatedEdges = graph.edges.filter { edge -> edge.from == current.id || edge.to == current.id }
    private val showAttributes = cursor.showAttributes

    fun pathBlock(): String {
        val crumbs = generateSequence(current) { node -> node.parentId?.let(nodesById::get) }
            .toList()
            .asReversed()
        if (crumbs.isEmpty()) return TextColors.gray(EMPTY)
        return crumbs.joinToString(TextColors.gray("  →  ")) { node ->
            val isCurrent = node.id == current.id
            val name = displayName(node)
            if (isCurrent) {
                TextStyles.bold(typeStyle(node.type)(name))
            } else {
                typeStyle(node.type)(name)
            }
        }
    }

    fun currentBlock(): String {
        val arrow = TextStyles.bold(TextColors.brightGreen("▶ "))
        val name = TextStyles.bold(TextColors.brightWhite(displayName(current)))
        val typeLabel = typeStyle(current.type)(current.type.name.lowercase())
        val parentLabel = parent?.let(::displayName)?.let(TextColors.cyan::invoke) ?: TextColors.gray(EMPTY)
        val summary = listOf(
            typeLabel,
            "parent " + parentLabel,
            TextColors.yellow("${children.size} children"),
            TextColors.magenta("${current.attributes.size} attrs"),
        ).joinToString(TextColors.gray(" · "))

        val lines = mutableListOf(
            "$arrow$name",
            "  $summary",
            "  ${TextColors.gray("id ")}${TextColors.white(current.id)}",
        )
        if (current.name != current.id && current.name != displayName(current)) {
            lines += "  ${TextColors.gray("label ")}${TextColors.white(current.name)}"
        }
        if (showAttributes && current.attributes.isNotEmpty()) {
            lines += "  ${TextColors.gray("attrs:")}"
            current.attributes.forEach { attr ->
                lines += "    ${TextColors.brightCyan("•")} ${TextColors.white(attr)}"
            }
        } else if (!showAttributes) {
            lines += "  " + TextColors.gray("(attributes hidden — press A)")
        }
        return lines.joinToString("\n")
    }

    fun neighborhoodBlock(): String {
        val parentName = parent?.let(::displayName)?.let(TextColors.cyan::invoke) ?: TextColors.gray(EMPTY)
        val prevName = siblingAt(-1)?.let(::displayName)?.let(TextColors.gray::invoke)
            ?: TextColors.gray(EMPTY)
        val nextName = siblingAt(1)?.let(::displayName)?.let(TextColors.gray::invoke)
            ?: TextColors.gray(EMPTY)
        val firstChildName = children.firstOrNull()?.let(::displayName)?.let(TextColors.yellow::invoke)
            ?: TextColors.gray(EMPTY)
        val curr = TextStyles.bold(TextColors.brightGreen(displayName(current)))
        val pipe = TextColors.gray("│")
        val arrowLeft = TextColors.gray("←")
        val arrowRight = TextColors.gray("→")
        return buildString {
            appendLine("        $parentName")
            appendLine("          $pipe")
            appendLine("  $prevName  $arrowLeft  $curr  $arrowRight  $nextName")
            appendLine("          $pipe")
            append("        $firstChildName")
        }
    }

    fun childrenBlock(): String {
        if (children.isEmpty()) return TextColors.gray(EMPTY)
        return children.mapIndexed { index, child ->
            val number = TextColors.gray("${(index + 1).toString().padStart(2)}.")
            val name = TextColors.white(displayName(child))
            val typeLabel = typeStyle(child.type)(child.type.name.lowercase())
            "$number $name  $typeLabel"
        }.joinToString("\n")
    }

    fun relationsBlock(): String {
        if (relatedEdges.isEmpty()) return TextColors.gray(EMPTY)
        val lines = relatedEdges.take(MAX_RELATIONS).map { edge -> describeEdge(edge) }
        val overflow = (relatedEdges.size - MAX_RELATIONS).takeIf { it > 0 }
            ?.let { TextColors.gray("… $it more") }
        return (lines + listOfNotNull(overflow)).joinToString("\n")
    }

    private fun siblingAt(offset: Int): MetricsGraphNode? {
        if (currentSiblingIndex == -1 || siblings.size < 2) return null
        val index = (currentSiblingIndex + offset + siblings.size) % siblings.size
        return siblings[index]
    }

    private fun describeEdge(edge: MetricsGraphEdge): String {
        val outbound = edge.from == current.id
        val otherId = if (outbound) edge.to else edge.from
        val other = nodesById[otherId]
        val relation = if (outbound) edge.edgeType.forwardLabel() else edge.edgeType.inverseLabel()
        val styledRelation = edgeStyle(edge.edgeType)(relation.padEnd(14))
        val target = other?.let { TextColors.white(displayName(it)) } ?: TextColors.gray(otherId)
        val weight = edge.weight.takeIf { it > 1 }?.let {
            "  " + TextColors.gray("weight ") + TextColors.brightWhite(it.toString())
        }.orEmpty()
        return "$styledRelation $target$weight"
    }

    private fun displayName(node: MetricsGraphNode): String =
        node.name.substringAfterLast('/').substringAfterLast(':').substringAfterLast('.')

    private fun typeStyle(type: MetricsGraphNodeType): TextStyle = when (type) {
        MetricsGraphNodeType.SYMBOL -> TextColors.brightCyan
        MetricsGraphNodeType.FILE -> TextColors.brightBlue
        MetricsGraphNodeType.REFERENCE_EDGE -> TextColors.brightMagenta
    }

    private fun edgeStyle(type: MetricsGraphEdgeType): TextStyle = when (type) {
        MetricsGraphEdgeType.CONTAINS -> TextColors.brightCyan
        MetricsGraphEdgeType.REFERENCES -> TextColors.brightYellow
        MetricsGraphEdgeType.REFERENCED_BY -> TextColors.brightMagenta
    }

    companion object {
        private const val EMPTY = "∅"
        private const val MAX_RELATIONS = 8
    }
}

private fun MetricsGraphEdgeType.forwardLabel(): String = when (this) {
    MetricsGraphEdgeType.CONTAINS -> "contains"
    MetricsGraphEdgeType.REFERENCED_BY -> "referenced by"
    MetricsGraphEdgeType.REFERENCES -> "references"
}

private fun MetricsGraphEdgeType.inverseLabel(): String = when (this) {
    MetricsGraphEdgeType.CONTAINS -> "contained by"
    MetricsGraphEdgeType.REFERENCED_BY -> "references"
    MetricsGraphEdgeType.REFERENCES -> "referenced by"
}
