package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.rgb
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.RenderScope
import kotlinx.serialization.Serializable

@Serializable
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
        val codePreview: String? = null,
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
        is KotterDemoStreamEntry.Content -> buildStreamPlainText(entry)
    }
}

private fun buildStreamPlainText(entry: KotterDemoStreamEntry.Content): String = buildString {
    append(tonePrefix(entry.tone))
    append(' ')
    append(entry.text)
    entry.codePreview?.let { code ->
        append("  `")
        append(code)
        append('`')
    }
}

private fun RenderScope.renderStreamLine(entry: KotterDemoStreamEntry.Content) {
    val prefix = tonePrefix(entry.tone)
    val toneRgb = TranscriptPalette.toneColor(entry.tone)
    if (toneRgb != null) {
        rgb(toneRgb) {
            textLine(buildStreamPlainText(entry))
        }
    } else if (entry.codePreview != null) {
        textLine(buildStreamPlainText(entry))
    } else {
        textLine("$prefix ${entry.text}")
    }
}

internal fun tonePrefix(tone: KotterDemoStreamTone): String = when (tone) {
    KotterDemoStreamTone.COMMAND -> "$"
    KotterDemoStreamTone.CONFIRMED -> "✓"
    KotterDemoStreamTone.FLAGGED -> "⚑"
    KotterDemoStreamTone.ERROR -> "✕"
    KotterDemoStreamTone.DETAIL -> "•"
    KotterDemoStreamTone.STRUCTURE -> "·"
}
