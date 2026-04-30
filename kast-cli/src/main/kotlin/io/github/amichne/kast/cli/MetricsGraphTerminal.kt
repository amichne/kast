package io.github.amichne.kast.cli

import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsGraphNode
import java.io.InputStream
import java.io.PrintStream

internal class MetricsGraphTerminal(private val graph: MetricsGraph) {
    private val nodesById = graph.nodes.associateBy(MetricsGraphNode::id)

    fun run(
        input: InputStream,
        output: PrintStream,
    ): Int {
        val rawMode = TerminalRawMode.enter()
        rawMode.use { rawMode ->
            var current = nodesById.getValue(graph.focalNodeId)
            var showAttributes = true
            render(output, current, showAttributes)
            while (true) {
                val key = readKey(input)
                when (key) {
                    TerminalKey.QUIT -> return 0
                    TerminalKey.PARENT -> current.parentId?.let(nodesById::get)?.let { current = it }
                    TerminalKey.FIRST_CHILD -> current.children.firstOrNull()?.let(nodesById::get)?.let { current = it }
                    TerminalKey.PREVIOUS_SIBLING -> sibling(current, -1)?.let { current = it }
                    TerminalKey.NEXT_SIBLING -> sibling(current, 1)?.let { current = it }
                    TerminalKey.ATTRIBUTES -> showAttributes = !showAttributes
                    TerminalKey.IGNORED -> Unit
                }
                if (key != TerminalKey.IGNORED) {
                    render(output, current, showAttributes)
                }
            }
        }
        return 0
    }

    private fun render(
        output: PrintStream,
        current: MetricsGraphNode,
        showAttributes: Boolean,
    ) {
        val parent = current.parentId?.let(nodesById::get)
        val children = current.children.mapNotNull(nodesById::get)
        val siblings = siblingList(current)
        output.print("\u001b[2J\u001b[H")
        output.println("Kast graph visualizer")
        output.println("U parent · D/Enter child · ←/→ sibling · A attributes · Q quit")
        output.println("${graph.index.symbolCount} symbols · ${graph.index.fileCount} files · ${graph.index.referenceCount} refs · depth ${graph.index.maxDepth}")
        output.println()
        output.println("▶ ${current.name}")
        output.println("  type: ${current.type}")
        output.println("  node: ${current.id}")
        output.println("  parent: ${parent?.name ?: "∅"}")
        output.println("  peers: ${siblings.joinToString { if (it.id == current.id) "[${it.name}]" else it.name }.ifBlank { "∅" }}")
        output.println()
        output.println("children")
        if (children.isEmpty()) {
            output.println("  ∅")
        } else {
            children.forEachIndexed { index, child ->
                output.println("  ${index + 1}. ${child.name} [${child.type}]")
            }
        }
        output.println()
        if (showAttributes) {
            output.println("attributes / members")
            if (current.attributes.isEmpty()) {
                output.println("  ∅")
            } else {
                current.attributes.forEach { output.println("  - $it") }
            }
            output.println()
        }
        output.println("relational context")
        val visibleEdges = graph.edges.filter { edge -> edge.from == current.id || edge.to == current.id }
        if (visibleEdges.isEmpty()) {
            output.println("  ∅")
        } else {
            visibleEdges.forEach { edge -> output.println("  ${edge.from} -${edge.edgeType}/${edge.weight}-> ${edge.to}") }
        }
        output.flush()
    }

    private fun sibling(
        current: MetricsGraphNode,
        direction: Int,
    ): MetricsGraphNode? {
        val siblings = siblingList(current)
        val index = siblings.indexOfFirst { it.id == current.id }
        if (index == -1 || siblings.size < 2) return null
        return siblings[(index + direction + siblings.size) % siblings.size]
    }

    private fun siblingList(current: MetricsGraphNode): List<MetricsGraphNode> {
        val parent = current.parentId?.let(nodesById::get) ?: return listOf(current)
        return parent.children.mapNotNull(nodesById::get)
    }

    private fun readKey(input: InputStream): TerminalKey {
        return when (val first = input.read()) {
            -1, 'q'.code, 'Q'.code -> TerminalKey.QUIT
            'u'.code, 'U'.code -> TerminalKey.PARENT
            'd'.code, 'D'.code, '\n'.code, '\r'.code -> TerminalKey.FIRST_CHILD
            'a'.code, 'A'.code -> TerminalKey.ATTRIBUTES
            27 -> readEscape(input)
            else -> TerminalKey.IGNORED
        }
    }

    private fun readEscape(input: InputStream): TerminalKey {
        if (input.read() != '['.code) return TerminalKey.IGNORED
        return when (input.read()) {
            'D'.code -> TerminalKey.PREVIOUS_SIBLING
            'C'.code -> TerminalKey.NEXT_SIBLING
            else -> TerminalKey.IGNORED
        }
    }
}

private enum class TerminalKey {
    PARENT,
    FIRST_CHILD,
    PREVIOUS_SIBLING,
    NEXT_SIBLING,
    ATTRIBUTES,
    QUIT,
    IGNORED,
}

private class TerminalRawMode private constructor(private val active: Boolean) : AutoCloseable {
    override fun close() {
        if (active) {
            runCatching { ProcessBuilder("sh", "-c", "stty sane < /dev/tty").start().waitFor() }
        }
    }

    companion object {
        fun enter(): TerminalRawMode {
            val exitCode = runCatching {
                ProcessBuilder("sh", "-c", "stty raw -echo < /dev/tty").start().waitFor()
            }.getOrDefault(1)
            return TerminalRawMode(exitCode == 0)
        }
    }
}
