package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.CliTextTheme
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Pure rendering of a [DemoScript] into an ANSI (or plain-text) transcript.
 * Kept host-free so tests can assert against the exact string output.
 */
internal class DemoRenderer(
    private val theme: CliTextTheme,
    private val width: Int = DEFAULT_WIDTH,
    private val ansiEnabled: Boolean = true,
) {
    fun render(script: DemoScript): String = buildString {
        script.scenes.forEach { scene -> renderScene(scene) }
    }

    private fun StringBuilder.renderScene(scene: DemoScene) {
        when (scene) {
            is DemoScene.Panel -> renderPanel(scene)
            is DemoScene.SectionHeading -> renderSectionHeading(scene)
            is DemoScene.StepProgress -> appendLine("${styleIcon("›", "1;34")} ${scene.message}")
            is DemoScene.StepOutcome -> renderStepOutcome(scene)
            is DemoScene.StepBody -> renderStepBody(scene)
            is DemoScene.ComparisonTable -> renderComparisonTable(scene)
            is DemoScene.BlankLine -> appendLine()
        }
    }

    private fun StringBuilder.renderPanel(panel: DemoScene.Panel) {
        val inner = max(MIN_PANEL_WIDTH, width - 2)
        val border = styleAnsi("1;36", "─".repeat(inner))
        val titleText = styleAnsi("1;37", panel.title)
        val topRule = "${styleAnsi("1;36", "┌")}$border${styleAnsi("1;36", "┐")}"
        val bottomRule = "${styleAnsi("1;36", "└")}$border${styleAnsi("1;36", "┘")}"
        val titleBar = "${styleAnsi("1;36", "│ ")}$titleText${padTo(panel.title.length, inner - 2)}${styleAnsi("1;36", " │")}"
        val separator = "${styleAnsi("1;36", "├")}$border${styleAnsi("1;36", "┤")}"
        appendLine(topRule)
        appendLine(titleBar)
        appendLine(separator)
        panel.lines.forEach { line ->
            val text = emphasise(line.emphasis, line.text)
            val pad = padTo(line.text.length, inner - 2)
            appendLine("${styleAnsi("1;36", "│ ")}$text$pad${styleAnsi("1;36", " │")}")
        }
        appendLine(bottomRule)
    }

    private fun StringBuilder.renderSectionHeading(heading: DemoScene.SectionHeading) {
        val title = heading.title
        val fill = max(0, width - title.length - 4)
        appendLine()
        appendLine("${styleAnsi("1;36", "──")} ${styleAnsi("1;37", title)} ${styleAnsi("2", "─".repeat(fill))}")
        appendLine()
    }

    private fun StringBuilder.renderStepOutcome(outcome: DemoScene.StepOutcome) {
        val icon = when (outcome.outcome) {
            StepResult.SUCCESS -> styleIcon("✓", "1;32")
            StepResult.FAILURE -> styleIcon("✕", "1;31")
            StepResult.INFO -> styleIcon("•", "33")
        }
        val elapsed = outcome.elapsed?.let { " ${styleAnsi("2", "(${formatDuration(it)})")}" } ?: ""
        appendLine("$icon ${outcome.message}$elapsed")
    }

    private fun StringBuilder.renderStepBody(body: DemoScene.StepBody) {
        body.lines.forEach { line ->
            val tag = when (line.tag) {
                BodyLineTag.NONE -> ""
                BodyLineTag.COMMENT -> " ${styleAnsi("2", "← comment")}"
                BodyLineTag.STRING -> " ${styleAnsi("2", "← string")}"
                BodyLineTag.IMPORT -> " ${styleAnsi("2", "← import")}"
                BodyLineTag.SUBSTRING -> " ${styleAnsi("2", "← substring")}"
                BodyLineTag.CORRECT -> ""
            }
            if (line.text.isEmpty() && line.tag == BodyLineTag.NONE) {
                appendLine()
                return@forEach
            }
            val prefix = styleAnsi("2", "  │ ")
            appendLine("$prefix${emphasise(line.emphasis, line.text)}$tag")
        }
    }

    private fun StringBuilder.renderComparisonTable(table: DemoScene.ComparisonTable) {
        val allRows = listOf(table.header) + table.rows
        val metricWidth = max(table.header.first.length, table.rows.maxOfOrNull { it.first.length } ?: 0)
        val leftWidth = max(table.header.second.length, table.rows.maxOfOrNull { it.second.length } ?: 0)
        val rightWidth = max(table.header.third.length, table.rows.maxOfOrNull { it.third.length } ?: 0)
        val top = "${styleAnsi("1;36", "┌")}${styleAnsi("1;36", "─".repeat(metricWidth + 2))}${styleAnsi("1;36", "┬")}${styleAnsi("1;36", "─".repeat(leftWidth + 2))}${styleAnsi("1;36", "┬")}${styleAnsi("1;36", "─".repeat(rightWidth + 2))}${styleAnsi("1;36", "┐")}"
        val mid = "${styleAnsi("1;36", "├")}${styleAnsi("1;36", "─".repeat(metricWidth + 2))}${styleAnsi("1;36", "┼")}${styleAnsi("1;36", "─".repeat(leftWidth + 2))}${styleAnsi("1;36", "┼")}${styleAnsi("1;36", "─".repeat(rightWidth + 2))}${styleAnsi("1;36", "┤")}"
        val bottom = "${styleAnsi("1;36", "└")}${styleAnsi("1;36", "─".repeat(metricWidth + 2))}${styleAnsi("1;36", "┴")}${styleAnsi("1;36", "─".repeat(leftWidth + 2))}${styleAnsi("1;36", "┴")}${styleAnsi("1;36", "─".repeat(rightWidth + 2))}${styleAnsi("1;36", "┘")}"
        val pipe = styleAnsi("1;36", "│")
        fun renderRow(row: Triple<String, String, String>, strongRight: Boolean): String {
            val metric = row.first.padEnd(metricWidth)
            val left = row.second.padEnd(leftWidth)
            val right = if (strongRight) styleAnsi("1;37", row.third.padEnd(rightWidth)) else row.third.padEnd(rightWidth)
            return "$pipe $metric $pipe $left $pipe $right $pipe"
        }
        appendLine(top)
        appendLine(renderRow(table.header, strongRight = true))
        appendLine(mid)
        allRows.drop(1).forEachIndexed { index, row ->
            appendLine(renderRow(row, strongRight = true))
            if (index < table.rows.size - 1) appendLine(mid)
        }
        appendLine(bottom)
    }

    private fun emphasise(emphasis: LineEmphasis, text: String): String = when (emphasis) {
        LineEmphasis.NORMAL -> text
        LineEmphasis.DIM -> styleAnsi("2", text)
        LineEmphasis.STRONG -> styleAnsi("1;37", text)
        LineEmphasis.SUCCESS -> styleAnsi("1;32", text)
        LineEmphasis.WARN -> styleAnsi("33", text)
        LineEmphasis.ERROR -> styleAnsi("1;31", text)
    }

    private fun styleAnsi(code: String, text: String): String =
        if (ansiEnabled) "\u001B[${code}m$text\u001B[0m" else text

    private fun styleIcon(icon: String, code: String): String = styleAnsi(code, icon)

    private fun padTo(current: Int, target: Int): String =
        if (current >= target) "" else " ".repeat(target - current)

    @Suppress("UNUSED_PARAMETER")
    private fun themeTitle(text: String): String = theme.title(text)

    private fun formatDuration(duration: Duration): String {
        val ms = duration.toDouble(DurationUnit.MILLISECONDS)
        return if (ms < 1_000) "${ms.toInt()}ms" else "%.2fs".format(ms / 1_000)
    }

    companion object {
        const val DEFAULT_WIDTH: Int = 96
        const val MIN_PANEL_WIDTH: Int = 58
    }
}
