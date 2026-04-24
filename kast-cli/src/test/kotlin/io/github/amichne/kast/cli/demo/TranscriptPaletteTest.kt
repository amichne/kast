package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.text.RGB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptPaletteTest {
    @Test
    fun `palette exposes distinct RGB values for every tone`() {
        val allColors = listOf(
            TranscriptPalette.COMMAND,
            TranscriptPalette.SUCCESS,
            TranscriptPalette.WARNING,
            TranscriptPalette.ERROR,
            TranscriptPalette.CODE,
            TranscriptPalette.PATH,
            TranscriptPalette.BORDER,
            TranscriptPalette.TITLE,
        )
        assertEquals(allColors.size, allColors.toSet().size, "Every palette slot must map to a distinct RGB value")
    }

    @Test
    fun `toneColor returns the documented mapping for each stream tone`() {
        assertEquals(TranscriptPalette.COMMAND, TranscriptPalette.toneColor(KotterDemoStreamTone.COMMAND))
        assertEquals(TranscriptPalette.SUCCESS, TranscriptPalette.toneColor(KotterDemoStreamTone.CONFIRMED))
        assertEquals(TranscriptPalette.WARNING, TranscriptPalette.toneColor(KotterDemoStreamTone.FLAGGED))
        assertEquals(TranscriptPalette.ERROR, TranscriptPalette.toneColor(KotterDemoStreamTone.ERROR))
        assertEquals(TranscriptPalette.BORDER, TranscriptPalette.toneColor(KotterDemoStreamTone.STRUCTURE))
    }

    @Test
    fun `all palette RGB components are in 0-255 range`() {
        val allColors = listOf(
            TranscriptPalette.COMMAND,
            TranscriptPalette.SUCCESS,
            TranscriptPalette.WARNING,
            TranscriptPalette.ERROR,
            TranscriptPalette.CODE,
            TranscriptPalette.PATH,
            TranscriptPalette.BORDER,
            TranscriptPalette.TITLE,
        )
        allColors.forEach { color ->
            assertTrue(color.r in 0..255 && color.g in 0..255 && color.b in 0..255) {
                "RGB out of range: $color"
            }
        }
    }

    @Test
    fun `DETAIL tone returns null since it uses default terminal color`() {
        assertEquals(null, TranscriptPalette.toneColor(KotterDemoStreamTone.DETAIL))
    }
}
