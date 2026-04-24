package io.github.amichne.kast.cli.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptHeightTest {
    @Test
    fun `computed height fills terminal minus chrome for no-branch layout`() {
        val height = transcriptVisibleLines(terminalHeight = 40, hasBranches = false, branchGridLineCount = 0)
        // Chrome = 19 (act header 6 + blank 1 + status 7 + blank 1 + transcript chrome 4)
        assertEquals(21, height)
    }

    @Test
    fun `computed height accounts for branch overhead`() {
        val height = transcriptVisibleLines(terminalHeight = 50, hasBranches = true, branchGridLineCount = 8)
        // Chrome = 19 + branch overhead (1 blank + 5 caption panel + 8 grid) = 33
        assertEquals(17, height)
    }

    @Test
    fun `minimum height is enforced for tiny terminals`() {
        val height = transcriptVisibleLines(terminalHeight = 20, hasBranches = false, branchGridLineCount = 0)
        assertTrue(height >= MIN_TRANSCRIPT_VISIBLE_LINES, "expected at least $MIN_TRANSCRIPT_VISIBLE_LINES, got $height")
    }

    @Test
    fun `height is capped at MAX_TERMINAL_HEIGHT for absurd values`() {
        val height = transcriptVisibleLines(terminalHeight = 10_000, hasBranches = false, branchGridLineCount = 0)
        // Capped terminal = MAX_TERMINAL_HEIGHT (80), minus chrome 19 = 61
        assertTrue(height <= MAX_TERMINAL_HEIGHT - BASE_CHROME_LINES, "height $height exceeds sensible cap")
    }

    @Test
    fun `height uses fallback when terminal height is zero or negative`() {
        val heightZero = transcriptVisibleLines(terminalHeight = 0, hasBranches = false, branchGridLineCount = 0)
        val heightNeg = transcriptVisibleLines(terminalHeight = -1, hasBranches = false, branchGridLineCount = 0)
        assertTrue(heightZero >= MIN_TRANSCRIPT_VISIBLE_LINES)
        assertTrue(heightNeg >= MIN_TRANSCRIPT_VISIBLE_LINES)
    }
}
