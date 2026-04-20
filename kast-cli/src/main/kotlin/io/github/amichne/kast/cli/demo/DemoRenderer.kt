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
        val contentWidth = inner - 2
        val border = styleAnsi("1;36", "─".repeat(inner))
        val topRule = "${styleAnsi("1;36", "┌")}$border${styleAnsi("1;36", "┐")}"
        val bottomRule = "${styleAnsi("1;36", "└")}$border${styleAnsi("1;36", "┘")}"
        val separator = "${styleAnsi("1;36", "├")}$border${styleAnsi("1;36", "┤")}"
        appendLine(topRule)
        appendLine(renderPanelRow(styleAnsi("1;37", truncate(panel.title, contentWidth)), truncate(panel.title, contentWidth).length, contentWidth))
        appendLine(separator)
        panel.lines.forEach { line ->
            if (line.prerendered != null) {
                // Pre-styled lines own their own ANSI / truncation. We still
                // pad them out to contentWidth using the plain text length.
                val rawLength = line.text.length.coerceAtMost(contentWidth)
                val rendered = if (line.text.length > contentWidth) {
                    // Caller gave us an oversize line. Fall back to a plain-text truncate so we don't overflow.
                    emphasise(line.emphasis, truncate(line.text, contentWidth))
                } else {
                    line.prerendered
                }
                appendLine(renderPanelRow(rendered, rawLength, contentWidth))
                return@forEach
            }
            val segments = wrapForWidth(line.text, contentWidth)
            if (segments.isEmpty()) {
                appendLine(renderPanelRow("", 0, contentWidth))
            } else {
                segments.forEach { segment ->
                    appendLine(renderPanelRow(emphasise(line.emphasis, segment), segment.length, contentWidth))
                }
            }
        }
        appendLine(bottomRule)
    }

    private fun renderPanelRow(rendered: String, rawLength: Int, contentWidth: Int): String {
        val pad = padTo(rawLength, contentWidth)
        return "${styleAnsi("1;36", "│ ")}$rendered$pad${styleAnsi("1;36", " │")}"
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
        val natMetric = max(table.header.first.length, table.rows.maxOfOrNull { it.first.length } ?: 0)
        val natLeft = max(table.header.second.length, table.rows.maxOfOrNull { it.second.length } ?: 0)
        val natRight = max(table.header.third.length, table.rows.maxOfOrNull { it.third.length } ?: 0)
        // Budget: width - (4 vertical bars + 6 single-space pads) = width - 10.
        val budget = max(natMetric + 12, width - 10)
        val metricCap = natMetric.coerceAtMost((budget * 0.22).toInt().coerceAtLeast(12))
        val remaining = (budget - metricCap).coerceAtLeast(24)
        val (leftCap, rightCap) = splitProportional(remaining, natLeft, natRight)

        val pipe = styleAnsi("1;36", "│")
        fun ruler(left: String, mid: String, right: String): String =
            "${styleAnsi("1;36", left)}${styleAnsi("1;36", "─".repeat(metricCap + 2))}${styleAnsi("1;36", mid)}${styleAnsi("1;36", "─".repeat(leftCap + 2))}${styleAnsi("1;36", mid)}${styleAnsi("1;36", "─".repeat(rightCap + 2))}${styleAnsi("1;36", right)}"

        val top = ruler("┌", "┬", "┐")
        val mid = ruler("├", "┼", "┤")
        val bottom = ruler("└", "┴", "┘")

        fun renderRow(row: Triple<String, String, String>, strongRight: Boolean) {
            val metricSegs = wrapForWidth(row.first, metricCap)
            val leftSegs = wrapForWidth(row.second, leftCap)
            val rightSegs = wrapForWidth(row.third, rightCap)
            val rows = maxOf(metricSegs.size, leftSegs.size, rightSegs.size)
            for (r in 0 until rows) {
                val metric = (metricSegs.getOrNull(r) ?: "").padEnd(metricCap)
                val left = (leftSegs.getOrNull(r) ?: "").padEnd(leftCap)
                val rightRaw = rightSegs.getOrNull(r) ?: ""
                val right = if (strongRight) styleAnsi("1;37", rightRaw) else rightRaw
                val rightPad = padTo(rightRaw.length, rightCap)
                appendLine("$pipe $metric $pipe $left $pipe $right$rightPad $pipe")
            }
        }

        appendLine(top)
        renderRow(table.header, strongRight = true)
        appendLine(mid)
        table.rows.forEachIndexed { index, row ->
            renderRow(row, strongRight = true)
            if (index < table.rows.size - 1) appendLine(mid)
        }
        appendLine(bottom)
    }

    /** Split [budget] between two natural widths, never shrinking either below [MIN_CELL]. */
    private fun splitProportional(budget: Int, leftNatural: Int, rightNatural: Int): Pair<Int, Int> {
        val total = (leftNatural + rightNatural).coerceAtLeast(1)
        val leftShare = ((budget.toDouble() * leftNatural) / total).toInt().coerceAtLeast(MIN_CELL)
        val rightShare = (budget - leftShare).coerceAtLeast(MIN_CELL)
        // Don't blow past the natural size — leaves breathing room when the
        // terminal is wider than the content.
        return leftShare.coerceAtMost(leftNatural.coerceAtLeast(MIN_CELL)) to
            rightShare.coerceAtMost(rightNatural.coerceAtLeast(MIN_CELL))
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

    /**
     * Word-wrap [text] to fit inside [width] columns. Tokens longer than
     * [width] are broken with a trailing ellipsis so the right border never
     * overflows. An empty input returns a single empty segment.
     */
    internal fun wrapForWidth(text: String, width: Int): List<String> {
        if (width <= 0) return emptyList()
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            out += current.toString()
            current = StringBuilder()
        }
        for (token in tokenizeForWrap(text)) {
            val chunks = breakLongToken(token, width)
            for (chunk in chunks) {
                val candidateLength = current.length + chunk.length
                when {
                    candidateLength <= width -> current.append(chunk)
                    current.isEmpty() -> {
                        out += chunk
                    }
                    else -> {
                        flush()
                        // Don't carry leading whitespace onto a fresh line.
                        val trimmed = chunk.trimStart()
                        if (trimmed.length <= width) current.append(trimmed) else out += trimmed.take(width - 1) + "…"
                    }
                }
            }
        }
        if (current.isNotEmpty()) flush()
        return if (out.isEmpty()) listOf("") else out.map { it.trimEnd() }
    }

    /** Split [text] into alternating word / whitespace tokens, preserving both. */
    private fun tokenizeForWrap(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val isSpace = text[i].isWhitespace()
            var j = i + 1
            while (j < text.length && text[j].isWhitespace() == isSpace) j += 1
            tokens += text.substring(i, j)
            i = j
        }
        return tokens
    }

    /** Break a single token into [width]-sized chunks, ellipsising the first
     *  chunk when the token cannot possibly fit. */
    private fun breakLongToken(token: String, width: Int): List<String> {
        if (token.length <= width) return listOf(token)
        // For oversize whitespace-only tokens (unlikely but harmless) drop to
        // a single space so we don't waste a wrapped line.
        if (token.isBlank()) return listOf(" ")
        // Hard truncation with a unicode ellipsis keeps the cell inside the box.
        return listOf(token.take(width - 1) + "…")
    }

    /** Columns available inside a panel's borders (after the `│ ` / ` │` gutters). */
    internal val panelContentWidth: Int
        get() = max(MIN_PANEL_WIDTH, width - 2) - 2

    internal fun truncate(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 1) + "…"

    /**
     * Right-biased truncation: keep the **tail** of [text] visible and
     * replace the leading prefix with `…`. Use for file paths and similar
     * strings where the rightmost segment (file name, line number) is the
     * most useful part for the reader.
     */
    internal fun truncateLeft(text: String, width: Int): String = when {
        width <= 0 -> ""
        text.length <= width -> text
        width == 1 -> "…"
        else -> "…" + text.takeLast(width - 1)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun themeTitle(text: String): String = theme.title(text)

    private fun formatDuration(duration: Duration): String {
        val ms = duration.toDouble(DurationUnit.MILLISECONDS)
        return if (ms < 1_000) "${ms.toInt()}ms" else "%.2fs".format(ms / 1_000)
    }

    companion object {
        const val DEFAULT_WIDTH: Int = 96
        const val MIN_PANEL_WIDTH: Int = 58
        const val MIN_CELL: Int = 10
    }
}
