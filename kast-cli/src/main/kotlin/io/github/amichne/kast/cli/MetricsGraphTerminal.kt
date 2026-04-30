package io.github.amichne.kast.cli

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.InputReceiver
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.input.receiveKeyEvents
import com.github.ajalt.mordant.terminal.Terminal
import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsGraphNode

internal class MetricsGraphTerminal(private val graph: MetricsGraph) {
    fun run(): Int {
        val terminal = Terminal()
        val navigator = MetricsGraphNavigator(graph)
        var cursor = MetricsGraphCursor(graph.focalNodeId)

        val animation = terminal.animation<MetricsGraphCursor> { frame ->
            MetricsGraphMordantView.render(graph, frame)
        }

        terminal.cursor.hide(showOnExit = true)
        animation.update(cursor)

        terminal.receiveKeyEvents { event ->
            if (event.isCtrlC) return@receiveKeyEvents InputReceiver.Status.Finished
            val action = event.toGraphAction() ?: return@receiveKeyEvents InputReceiver.Status.Continue

            if (action == GraphAction.Quit) {
                return@receiveKeyEvents InputReceiver.Status.Finished
            }

            cursor = navigator.reduce(cursor, action)
            animation.update(cursor)
            InputReceiver.Status.Continue
        }

        return 0
    }
}

internal data class MetricsGraphCursor(
    val currentNodeId: String,
    val showAttributes: Boolean = true,
)

internal enum class GraphAction {
    Parent,
    FirstChild,
    PreviousSibling,
    NextSibling,
    ToggleAttributes,
    Quit,
}

internal class MetricsGraphNavigator(private val graph: MetricsGraph) {
    private val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)

    fun reduce(cursor: MetricsGraphCursor, action: GraphAction): MetricsGraphCursor {
        val current = nodesById.getValue(cursor.currentNodeId)
        return when (action) {
            GraphAction.Parent ->
                current.parentId?.let { cursor.copy(currentNodeId = it) } ?: cursor

            GraphAction.FirstChild ->
                current.children.firstOrNull()?.let { cursor.copy(currentNodeId = it) } ?: cursor

            GraphAction.PreviousSibling ->
                sibling(current, -1)?.let { cursor.copy(currentNodeId = it.id) } ?: cursor

            GraphAction.NextSibling ->
                sibling(current, 1)?.let { cursor.copy(currentNodeId = it.id) } ?: cursor

            GraphAction.ToggleAttributes ->
                cursor.copy(showAttributes = !cursor.showAttributes)

            GraphAction.Quit -> cursor
        }
    }

    private fun sibling(current: MetricsGraphNode, direction: Int): MetricsGraphNode? {
        val parent = current.parentId?.let(nodesById::get) ?: return null
        val siblings = parent.children.mapNotNull(nodesById::get)
        val index = siblings.indexOfFirst { it.id == current.id }
        if (index == -1 || siblings.size < 2) return null
        return siblings[(index + direction + siblings.size) % siblings.size]
    }
}

private fun KeyboardEvent.toGraphAction(): GraphAction? {
    return when (key) {
        "ArrowUp" -> GraphAction.Parent
        "ArrowDown", "Enter" -> GraphAction.FirstChild
        "ArrowLeft" -> GraphAction.PreviousSibling
        "ArrowRight" -> GraphAction.NextSibling
        else -> when (key.lowercase()) {
            "u" -> GraphAction.Parent
            "d" -> GraphAction.FirstChild
            "a" -> GraphAction.ToggleAttributes
            "q" -> GraphAction.Quit
            else -> null
        }
    }
}
