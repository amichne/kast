package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.api.contract.CallNode
import java.nio.file.Path

internal const val CALL_TREE_PREVIEW_LIMIT: Int = 12

internal fun renderCallTreePreview(
    workspaceRoot: Path,
    root: CallNode,
    verbose: Boolean = true,
    limit: Int = CALL_TREE_PREVIEW_LIMIT,
): List<String> {
    val lines = mutableListOf<String>()
    val remaining = intArrayOf(limit)

    fun walk(node: CallNode, depth: Int) {
        if (remaining[0] <= 0) return
        remaining[0] -= 1
        val indent = "  ".repeat(depth.coerceAtLeast(0))
        val prefix = if (depth > 0) "├─ " else ""
        val symbol = node.symbol
        lines += "$indent$prefix${symbol.fqName.substringAfterLast('.')} (${symbol.kind})  ${Paths.locationLine(workspaceRoot, symbol.location, verbose)}"
        node.children.forEach { child -> walk(child, depth + 1) }
    }

    walk(root, depth = 0)
    return lines
}
