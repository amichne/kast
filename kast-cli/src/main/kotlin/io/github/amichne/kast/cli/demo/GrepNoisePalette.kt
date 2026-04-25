package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.RGB
import io.github.amichne.kast.cli.DemoTextMatchCategory

internal fun grepNoiseColor(c: DemoTextMatchCategory): RGB = when (c) {
    DemoTextMatchCategory.LIKELY_CORRECT -> TranscriptPalette.TITLE
    DemoTextMatchCategory.IMPORT -> TranscriptPalette.WARNING
    DemoTextMatchCategory.COMMENT -> DIM_RED
    DemoTextMatchCategory.STRING -> DIM_RED
    DemoTextMatchCategory.SUBSTRING -> TranscriptPalette.ERROR
}

internal fun grepNoisePrefix(c: DemoTextMatchCategory): String = when (c) {
    DemoTextMatchCategory.LIKELY_CORRECT -> "?"
    DemoTextMatchCategory.IMPORT -> "~"
    DemoTextMatchCategory.COMMENT -> "#"
    DemoTextMatchCategory.STRING -> "\""
    DemoTextMatchCategory.SUBSTRING -> "✕"
}

private val DIM_RED: RGB = RGB(175, 95, 95)
