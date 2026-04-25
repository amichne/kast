package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.RGB
import com.varabyte.kotter.foundation.text.rgb
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope

internal fun RenderScope.renderDualTranscriptPanel(
    leftHeader: String,
    leftLines: List<DualPaneLeftLine>,
    rightHeader: String,
    rightLines: List<KotterDemoTranscriptLine>,
    paneWidth: Int,
    paneHeight: Int,
    leftFooter: String,
    rightFooter: String,
    gap: Int = 1,
) {
    val leftRendered = dualTranscriptPaneLines(
        header = leftHeader,
        lines = leftLines.map { line -> "${grepNoisePrefix(line.category)} ${line.text}" },
        footer = leftFooter,
        paneWidth = paneWidth,
        paneHeight = paneHeight,
    )
    val rightRendered = dualTranscriptPaneLines(
        header = rightHeader,
        lines = rightLines.map { line -> "${tonePrefix(line.tone)} ${line.text}" },
        footer = rightFooter,
        paneWidth = paneWidth,
        paneHeight = paneHeight,
    )
    leftRendered.zip(rightRendered).forEachIndexed { rowIndex, (left, right) ->
        val lineKind = dualPaneLineKind(rowIndex, leftRendered.lastIndex)
        if (lineKind == DualPaneLineKind.BODY) {
            renderDualPaneBodyLine(
                left = left,
                leftLine = leftLines.getOrNull(rowIndex - DUAL_PANE_BODY_START_INDEX),
                right = right,
                rightLine = rightLines.getOrNull(rowIndex - DUAL_PANE_BODY_START_INDEX),
                gap = gap,
            )
        } else {
            structural { text(left) }
            text(" ".repeat(gap.coerceAtLeast(0)))
            structural { textLine(right) }
        }
    }
}

internal fun dualTranscriptPanelLines(
    leftHeader: String,
    leftLines: List<DualPaneLeftLine>,
    rightHeader: String,
    rightLines: List<KotterDemoTranscriptLine>,
    paneWidth: Int,
    paneHeight: Int,
    leftFooter: String,
    rightFooter: String,
    gap: Int = 1,
): List<String> {
    val left = dualTranscriptPaneLines(
        header = leftHeader,
        lines = leftLines.map { "${grepNoisePrefix(it.category)} ${it.text}" },
        footer = leftFooter,
        paneWidth = paneWidth,
        paneHeight = paneHeight,
    )
    val right = dualTranscriptPaneLines(
        header = rightHeader,
        lines = rightLines.map { "${tonePrefix(it.tone)} ${it.text}" },
        footer = rightFooter,
        paneWidth = paneWidth,
        paneHeight = paneHeight,
    )
    return left.zip(right).map { (leftLine, rightLine) -> "$leftLine${" ".repeat(gap.coerceAtLeast(0))}$rightLine" }
}

private fun dualTranscriptPaneLines(
    header: String,
    lines: List<String>,
    footer: String,
    paneWidth: Int,
    paneHeight: Int,
): List<String> {
    val width = paneWidth.coerceAtLeast(MIN_DUAL_TRANSCRIPT_PANE_WIDTH)
    val contentWidth = (width - 4).coerceAtLeast(1)
    val bodyHeight = paneHeight.coerceAtLeast(1)
    return buildList {
        add("┌${"─".repeat(width - 2)}┐")
        add(dualPaneRow(header, contentWidth))
        add("├${"─".repeat(width - 2)}┤")
        repeat(bodyHeight) { index ->
            add(dualPaneRow(lines.getOrNull(index).orEmpty(), contentWidth))
        }
        add("├${"─".repeat(width - 2)}┤")
        add(dualPaneRow(footer, contentWidth))
        add("└${"─".repeat(width - 2)}┘")
    }
}

private fun dualPaneRow(
    content: String,
    contentWidth: Int,
): String = "│ ${TextFit.truncate(content, contentWidth).padEnd(contentWidth)} │"

private enum class DualPaneLineKind {
    STRUCTURE,
    BODY,
}

private fun dualPaneLineKind(rowIndex: Int, lastIndex: Int): DualPaneLineKind =
    if (rowIndex in DUAL_PANE_BODY_START_INDEX..(lastIndex - DUAL_PANE_FOOTER_LINE_COUNT)) {
        DualPaneLineKind.BODY
    } else {
        DualPaneLineKind.STRUCTURE
    }

private fun RenderScope.renderDualPaneBodyLine(
    left: String,
    leftLine: DualPaneLeftLine?,
    right: String,
    rightLine: KotterDemoTranscriptLine?,
    gap: Int,
) {
    val leftColor = if (leftLine != null) grepNoiseColor(leftLine.category) else null
    val rightColor = if (rightLine != null) TranscriptPalette.toneColor(rightLine.tone) else null

    renderColoredDualPaneLine(left, leftColor)
    text(" ".repeat(gap.coerceAtLeast(0)))
    renderColoredDualPaneLine(right, rightColor)
    textLine()
}

private fun RenderScope.renderColoredDualPaneLine(
    line: String,
    color: RGB?,
) {
    if (color != null) {
        rgb(color) { text(line) }
    } else {
        text(line)
    }
}

private fun RenderScope.structural(block: RenderScope.() -> Unit) {
    rgb(TranscriptPalette.BORDER, scopedBlock = block)
}

private const val MIN_DUAL_TRANSCRIPT_PANE_WIDTH: Int = 8
private const val DUAL_PANE_BODY_START_INDEX: Int = 3
private const val DUAL_PANE_FOOTER_LINE_COUNT: Int = 3
