package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.rgb
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

internal fun RenderScope.renderScoreboard(rows: List<ScoreboardRow>, totalWidth: Int) {
    scoreboardGridLines(rows, totalWidth).forEach { line ->
        if ("★ NEW" in line) {
            renderScoreboardLineWithNewBadge(line)
        } else if (line.startsWith("┌") || line.startsWith("├") || line.startsWith("└") || line.startsWith("│")) {
            rgb(TranscriptPalette.BORDER) { textLine(line) }
        } else {
            textLine(line)
        }
    }
}

internal fun scoreboardGridLines(rows: List<ScoreboardRow>, totalWidth: Int): List<String> {
    val columnWidths = scoreboardColumnWidths(totalWidth.coerceAtLeast(MIN_SCOREBOARD_WIDTH))
    val columns = listOf("Metric", "grep", "kast", "Δ")
    return buildList {
        add(scoreboardRule(columnWidths, left = "┌", middle = "┬", right = "┐"))
        add(scoreboardRow(columnWidths, columns))
        add(scoreboardRule(columnWidths, left = "├", middle = "┼", right = "┤"))
        rows.forEach { row ->
            add(
                scoreboardRow(
                    columnWidths,
                    listOf(
                        row.metric,
                        row.grepValue,
                        row.kastValue,
                        if (row.isNewCapability) "★ NEW" else row.delta,
                    ),
                ),
            )
        }
        add(scoreboardRule(columnWidths, left = "└", middle = "┴", right = "┘"))
    }
}

private fun scoreboardColumnWidths(totalWidth: Int): List<Int> {
    val available = (totalWidth - SCOREBOARD_FRAME_WIDTH).coerceAtLeast(4)
    val metric = (available * 34) / 100
    val grep = (available * 22) / 100
    val kast = (available * 28) / 100
    val delta = (available - metric - grep - kast).coerceAtLeast(1)
    return listOf(metric, grep, kast, delta)
}

private fun scoreboardRule(
    columnWidths: List<Int>,
    left: String,
    middle: String,
    right: String,
): String = buildString {
    append(left)
    columnWidths.forEachIndexed { index, width ->
        append("─".repeat(width + 2))
        append(if (index == columnWidths.lastIndex) right else middle)
    }
}

private fun scoreboardRow(columnWidths: List<Int>, cells: List<String>): String = buildString {
    append("│")
    columnWidths.zip(cells).forEach { (width, cell) ->
        append(" ")
        append(TextFit.truncate(cell, width).padEnd(width))
        append(" │")
    }
}

private fun RenderScope.renderScoreboardLineWithNewBadge(line: String) {
    val badgeIndex = line.indexOf("★ NEW")
    if (badgeIndex < 0) {
        textLine(line)
        return
    }
    rgb(TranscriptPalette.BORDER) { text(line.take(badgeIndex)) }
    rgb(TranscriptPalette.SUCCESS) { text("★ NEW") }
    rgb(TranscriptPalette.BORDER) { textLine(line.drop(badgeIndex + "★ NEW".length)) }
}

private const val MIN_SCOREBOARD_WIDTH: Int = 72
private const val SCOREBOARD_FRAME_WIDTH: Int = 13
