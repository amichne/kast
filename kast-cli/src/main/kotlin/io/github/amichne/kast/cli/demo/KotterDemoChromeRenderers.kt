package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.white
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

internal data class KotterDemoPhaseChip(
    val label: String,
    val status: KotterDemoPhaseStatus,
)

internal data class KotterDemoPhaseBar(
    val phases: List<KotterDemoPhaseChip>,
) {
    init {
        require(phases.isNotEmpty()) { "Phase bar must render at least one phase." }
    }
}

internal enum class KotterDemoActivityStatus {
    RUNNING,
    COMPLETE,
}

internal data class KotterDemoActivityIndicator(
    val status: KotterDemoActivityStatus,
    val pulseVisible: Boolean,
)

internal data class KotterDemoActHeader(
    val title: String,
    val queryBar: KotterDemoQueryBar,
)

internal data class KotterDemoStatusPanel(
    val operationRail: List<KotterDemoOperationChip>,
    val phaseBar: KotterDemoPhaseBar,
    val activityIndicator: KotterDemoActivityIndicator,
    val controls: String,
)

internal fun RenderScope.renderActHeader(
    header: KotterDemoActHeader,
    panelContentWidth: Int,
) {
    renderPanel(
        title = "Live Demo",
        panelContentWidth = panelContentWidth,
        bodyLines = listOf(
            header.title,
            queryBarLine(header.queryBar),
        ),
    )
}

internal fun RenderScope.renderStatusPanel(
    statusPanel: KotterDemoStatusPanel,
    panelContentWidth: Int,
) {
    renderPanel(
        title = "Status",
        panelContentWidth = panelContentWidth,
        bodyLines = listOf(
            operationRailLine(statusPanel.operationRail),
            phaseStatusLine(statusPanel.phaseBar, statusPanel.activityIndicator),
            statusPanel.controls,
        ),
    )
}

internal fun RenderScope.renderPanel(
    title: String,
    panelContentWidth: Int,
    bodyLines: List<String>,
) {
    val width = panelContentWidth.coerceAtLeast(1)
    structural { textLine("┌${"─".repeat(width + 2)}┐") }
    renderPanelLine(title, width, tone = PanelTone.TITLE)
    structural { textLine("├${"─".repeat(width + 2)}┤") }
    if (bodyLines.isEmpty()) {
        renderPanelLine("", width)
    } else {
        bodyLines.forEach { line -> renderPanelLine(line, width) }
    }
    structural { textLine("└${"─".repeat(width + 2)}┘") }
}

internal fun RenderScope.renderOperationRail(operationRail: List<KotterDemoOperationChip>) {
    textLine(operationRailLine(operationRail))
}

internal fun RenderScope.renderQueryBar(queryBar: KotterDemoQueryBar) {
    textLine(queryBarLine(queryBar))
}

internal fun RenderScope.renderPhaseBar(phaseBar: KotterDemoPhaseBar) {
    text(phaseBarText(phaseBar))
}

internal fun RenderScope.renderActivityIndicator(indicator: KotterDemoActivityIndicator) {
    when (indicator.status) {
        KotterDemoActivityStatus.RUNNING ->
            if (indicator.pulseVisible) {
                yellow(isBright = true) { text("●") }
            } else {
                text("○")
            }

        KotterDemoActivityStatus.COMPLETE -> green(isBright = true) { text("●") }
    }
}

internal fun operationRailLine(operationRail: List<KotterDemoOperationChip>): String = buildString {
    append("Acts   ")
    operationRail.forEachIndexed { index, chip ->
        if (index > 0) append("  ")
        append(if (chip.active) "[${chip.label}]" else chip.label)
    }
}

internal fun queryBarLine(queryBar: KotterDemoQueryBar): String =
    "Command  ${queryBar.renderedCommand}${if (queryBar.cursorVisible) " █" else ""}"

internal fun phaseBarText(phaseBar: KotterDemoPhaseBar): String = buildString {
    phaseBar.phases.forEachIndexed { index, phase ->
        if (index > 0) append(" → ")
        append(
            when (phase.status) {
                KotterDemoPhaseStatus.PENDING -> phase.label
                KotterDemoPhaseStatus.ACTIVE -> "▶ ${phase.label}"
                KotterDemoPhaseStatus.COMPLETE -> "✓ ${phase.label}"
            },
        )
    }
}

internal fun phaseStatusLine(
    phaseBar: KotterDemoPhaseBar,
    indicator: KotterDemoActivityIndicator,
): String = "Phase  ${phaseBarText(phaseBar)}   ${activityIndicatorText(indicator)}"

internal fun activityIndicatorText(indicator: KotterDemoActivityIndicator): String = when (indicator.status) {
    KotterDemoActivityStatus.RUNNING -> if (indicator.pulseVisible) "● Live" else "○ Live"
    KotterDemoActivityStatus.COMPLETE -> "● Complete"
}

private enum class PanelTone {
    TITLE,
    BODY,
}

private fun RenderScope.renderPanelLine(
    textValue: String,
    width: Int,
    tone: PanelTone = PanelTone.BODY,
) {
    val content = TextFit.truncate(textValue, width).padEnd(width)
    structural { text("│ ") }
    when (tone) {
        PanelTone.TITLE -> white(isBright = true) { text(content) }
        PanelTone.BODY -> text(content)
    }
    structural { textLine(" │") }
}

private fun RenderScope.structural(block: RenderScope.() -> Unit) {
    black(isBright = true, scopedBlock = block)
}

// -- Colored transcript panel -----------------------------------------------

internal fun RenderScope.renderTranscriptPanel(
    title: String,
    panelContentWidth: Int,
    lines: List<KotterDemoTranscriptLine>,
) {
    val width = panelContentWidth.coerceAtLeast(1)
    structural { textLine("┌${"─".repeat(width + 2)}┐") }
    renderPanelLine(title, width, tone = PanelTone.TITLE)
    structural { textLine("├${"─".repeat(width + 2)}┤") }
    if (lines.isEmpty()) {
        renderPanelLine("", width)
    } else {
        lines.forEach { line -> renderTranscriptLine(line, width) }
    }
    structural { textLine("└${"─".repeat(width + 2)}┘") }
}

private fun RenderScope.renderTranscriptLine(
    line: KotterDemoTranscriptLine,
    width: Int,
) {
    val prefix = tonePrefix(line.tone)
    val content = TextFit.truncate("$prefix ${line.text}", width).padEnd(width)
    structural { text("│ ") }
    when (line.tone) {
        KotterDemoStreamTone.COMMAND -> cyan(isBright = true) { text(content) }
        KotterDemoStreamTone.CONFIRMED -> green(isBright = true) { text(content) }
        KotterDemoStreamTone.FLAGGED -> yellow(isBright = true) { text(content) }
        KotterDemoStreamTone.ERROR -> red(isBright = true) { text(content) }
        KotterDemoStreamTone.STRUCTURE -> black(isBright = true) { text(content) }
        KotterDemoStreamTone.DETAIL -> text(content)
    }
    structural { textLine(" │") }
}

private fun tonePrefix(tone: KotterDemoStreamTone): String = when (tone) {
    KotterDemoStreamTone.COMMAND -> "$"
    KotterDemoStreamTone.CONFIRMED -> "✓"
    KotterDemoStreamTone.FLAGGED -> "⚑"
    KotterDemoStreamTone.ERROR -> "✕"
    KotterDemoStreamTone.DETAIL -> "•"
    KotterDemoStreamTone.STRUCTURE -> "·"
}

// -- Colored status panel ---------------------------------------------------

internal fun RenderScope.renderColoredStatusPanel(
    statusPanel: KotterDemoStatusPanel,
    panelContentWidth: Int,
) {
    val width = panelContentWidth.coerceAtLeast(1)
    structural { textLine("┌${"─".repeat(width + 2)}┐") }
    renderPanelLine("Status", width, tone = PanelTone.TITLE)
    structural { textLine("├${"─".repeat(width + 2)}┤") }
    renderColoredOperationRail(statusPanel.operationRail, width)
    renderColoredPhaseBar(statusPanel.phaseBar, statusPanel.activityIndicator, width)
    renderPanelLine(statusPanel.controls, width)
    structural { textLine("└${"─".repeat(width + 2)}┘") }
}

private fun RenderScope.renderColoredOperationRail(
    operationRail: List<KotterDemoOperationChip>,
    width: Int,
) {
    structural { text("│ ") }
    val railContent = StringBuilder()
    railContent.append("Acts   ")
    operationRail.forEachIndexed { index, chip ->
        if (index > 0) railContent.append("  ")
        railContent.append(if (chip.active) "[${chip.label}]" else chip.label)
    }
    val plainRail = railContent.toString()
    val truncated = TextFit.truncate(plainRail, width)
    val padded = truncated.padEnd(width)

    // Re-render with color: highlight the active chip
    text("Acts   ")
    operationRail.forEachIndexed { index, chip ->
        if (index > 0) text("  ")
        if (chip.active) {
            cyan(isBright = true) { text("[${chip.label}]") }
        } else {
            text(chip.label)
        }
    }
    val renderedLength = plainRail.length.coerceAtMost(width)
    if (renderedLength < width) text(" ".repeat(width - renderedLength))
    structural { textLine(" │") }
}

private fun RenderScope.renderColoredPhaseBar(
    phaseBar: KotterDemoPhaseBar,
    indicator: KotterDemoActivityIndicator,
    width: Int,
) {
    structural { text("│ ") }
    text("Phase  ")
    phaseBar.phases.forEachIndexed { index, phase ->
        if (index > 0) text(" → ")
        when (phase.status) {
            KotterDemoPhaseStatus.PENDING -> text(phase.label)
            KotterDemoPhaseStatus.ACTIVE -> yellow(isBright = true) { text("▶ ${phase.label}") }
            KotterDemoPhaseStatus.COMPLETE -> green(isBright = true) { text("✓ ${phase.label}") }
        }
    }
    text("   ")
    when (indicator.status) {
        KotterDemoActivityStatus.RUNNING ->
            if (indicator.pulseVisible) {
                yellow(isBright = true) { text("● Live") }
            } else {
                text("○ Live")
            }
        KotterDemoActivityStatus.COMPLETE -> green(isBright = true) { text("● Complete") }
    }
    // Pad to width — approximate: the plain-text version gives us the right length
    val plainPhase = phaseStatusLine(phaseBar, indicator)
    val renderedLength = plainPhase.length.coerceAtMost(width)
    if (renderedLength < width) text(" ".repeat(width - renderedLength))
    structural { textLine(" │") }
}
