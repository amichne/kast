package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.black
import com.varabyte.kotter.foundation.text.cyan
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.render.RenderScope

internal enum class KotterDemoStreamTone {
    COMMAND,
    CONFIRMED,
    FLAGGED,
    ERROR,
    DETAIL,
    STRUCTURE,
}

internal sealed interface KotterDemoStreamEntry {
    data object Separator : KotterDemoStreamEntry

    data class Content(
        val text: String,
        val tone: KotterDemoStreamTone = KotterDemoStreamTone.DETAIL,
    ) : KotterDemoStreamEntry {
        init {
            require(text.isNotEmpty()) { "Stream content must be non-empty. Use Separator for blank lines." }
        }
    }
}

internal data class KotterDemoStreamBlock(
    val entries: List<KotterDemoStreamEntry>,
)

internal fun RenderScope.renderStreamBlock(block: KotterDemoStreamBlock) {
    block.entries.forEach { entry ->
        when (entry) {
            KotterDemoStreamEntry.Separator -> textLine()
            is KotterDemoStreamEntry.Content -> renderStreamLine(entry)
        }
    }
}

internal fun streamLines(block: KotterDemoStreamBlock): List<String> = block.entries.map { entry ->
    when (entry) {
        KotterDemoStreamEntry.Separator -> ""
        is KotterDemoStreamEntry.Content -> "${tonePrefix(entry.tone)} ${entry.text}"
    }
}

private fun RenderScope.renderStreamLine(entry: KotterDemoStreamEntry.Content) {
    val line = "${tonePrefix(entry.tone)} ${entry.text}"
    when (entry.tone) {
        KotterDemoStreamTone.COMMAND -> cyan(isBright = true) { textLine(line) }
        KotterDemoStreamTone.CONFIRMED -> green(isBright = true) { textLine(line) }
        KotterDemoStreamTone.FLAGGED -> yellow(isBright = true) { textLine(line) }
        KotterDemoStreamTone.ERROR -> red(isBright = true) { textLine(line) }
        KotterDemoStreamTone.STRUCTURE -> black(isBright = true) { textLine(line) }
        KotterDemoStreamTone.DETAIL -> textLine(line)
    }
}

private fun tonePrefix(tone: KotterDemoStreamTone): String = when (tone) {
    KotterDemoStreamTone.COMMAND -> "$"
    KotterDemoStreamTone.CONFIRMED -> "✓"
    KotterDemoStreamTone.FLAGGED -> "⚑"
    KotterDemoStreamTone.ERROR -> "✕"
    KotterDemoStreamTone.DETAIL -> "•"
    KotterDemoStreamTone.STRUCTURE -> "·"
}
