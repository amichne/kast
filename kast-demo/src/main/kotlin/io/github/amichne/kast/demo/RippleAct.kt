package io.github.amichne.kast.demo

import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.color
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

/**
 * Renders Act 3 — Caller Graph (tree visualization).
 */
fun RenderScope.renderRippleAct(root: CallerNode, depth: Int) {
    renderActHeader(
        actNumber = 3,
        totalActs = 3,
        title = "Caller Graph (depth $depth)",
        subtitle = "",
    )
    textLine()

    // Render tree
    renderTreeNode(root, prefix = "  ", isLast = true, isRoot = true, currentDepth = 0)
    textLine()

    // Summary — count all rendered nodes (including duplicates across branches)
    val allNodes = collectNodes(root)
    val uniqueModules = allNodes.map { it.module }.toSet()
    val moduleWord = if (uniqueModules.size == 1) "module" else "modules"
    val symbolWord = if (allNodes.size == 1) "symbol" else "symbols"

    text("  ")
    green(isBright = true) { text("${uniqueModules.size} $moduleWord") }
    text(". ")
    green(isBright = true) { text("${allNodes.size} $symbolWord") }
    textLine(" reachable in $depth hops.")

    black(isBright = true) { textLine("  Every edge is a compiler-verified call site.") }
    textLine()
    black(isBright = true) {
        textLine("  kast demo --symbol <fqn> --depth ${depth + 1}")
    }
}

private fun RenderScope.renderTreeNode(
    node: CallerNode,
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean,
    currentDepth: Int,
) {
    if (isRoot) {
        text(prefix)
        cyan(isBright = true) { text(node.symbolName) }
    } else {
        text(prefix)
        val branch = if (isLast) "└── " else "├── "
        text(branch)
        when {
            currentDepth <= 1 -> yellow(isBright = true) { text(node.symbolName) }
            else -> text(node.symbolName)
        }
    }

    // Module label
    text("  ")
    color(ModulePalette.colorFor(node.module)) {
        text("[${node.module}]")
    }
    textLine()

    // Render children
    val childPrefix = if (isRoot) {
        prefix
    } else {
        prefix + if (isLast) "    " else "│   "
    }

    node.children.forEachIndexed { index, child ->
        renderTreeNode(
            node = child,
            prefix = childPrefix,
            isLast = index == node.children.lastIndex,
            isRoot = false,
            currentDepth = currentDepth + 1,
        )
    }
}

private fun collectNodes(node: CallerNode): List<CallerNode> {
    return listOf(node) + node.children.flatMap { collectNodes(it) }
}
