package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.rgb
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

internal fun RenderScope.renderBranchGrid(lines: List<String>) {
    lines.forEach { line ->
        if (line.startsWith("┌") || line.startsWith("├") || line.startsWith("└") || line.startsWith("│")) {
            structural { textLine(line) }
        } else {
            textLine(line)
        }
    }
}

/** Overload for callers that only have a [KotterDemoBranchGrid] (e.g. tests). Prefer passing pre-computed lines. */
internal fun RenderScope.renderBranchGrid(branchGrid: KotterDemoBranchGrid) =
    renderBranchGrid(branchGridLines(branchGrid))

internal fun branchGridLines(branchGrid: KotterDemoBranchGrid): List<String> {
    if (branchGrid.columns.isEmpty()) return emptyList()

    return buildList {
        add(gridRule(branchGrid, left = "┌", middle = "┬", right = "┐"))
        add(gridRow(branchGrid, branchGrid.columns.map(KotterDemoBranchColumn::header)))
        add(gridRule(branchGrid, left = "├", middle = "┼", right = "┤"))
        repeat(branchGrid.columns.maxOf { it.lines.size }) { rowIndex ->
            add(gridRow(branchGrid, branchGrid.columns.map { column -> column.lines.getOrNull(rowIndex).orEmpty() }))
        }
        add(gridRule(branchGrid, left = "├", middle = "┼", right = "┤"))
        add(gridRow(branchGrid, branchGrid.columns.map(KotterDemoBranchColumn::summary)))
        add(gridRule(branchGrid, left = "└", middle = "┴", right = "┘"))
    }
}

private fun gridRule(
    branchGrid: KotterDemoBranchGrid,
    left: String,
    middle: String,
    right: String,
): String = buildString {
    append(left)
    branchGrid.columns.forEachIndexed { index, _ ->
        append("─".repeat(branchGrid.columnWidth + 2))
        append(if (index == branchGrid.columns.lastIndex) right else middle)
    }
}

private fun gridRow(
    branchGrid: KotterDemoBranchGrid,
    cells: List<String>,
): String = buildString {
    append("│")
    cells.forEach { cell ->
        append(" ")
        append(boundedCell(cell, branchGrid.columnWidth))
        append(" │")
    }
}

private fun boundedCell(
    text: String,
    width: Int,
): String = TextFit.truncate(text, width).padEnd(width)

private fun RenderScope.structural(block: RenderScope.() -> Unit) {
    rgb(TranscriptPalette.BORDER, scopedBlock = block)
}
