package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.DemoTextMatchCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class GrepNoisePaletteTest {
    @Test
    fun `every text match category has a stable prefix and color`() {
        DemoTextMatchCategory.entries.forEach { category ->
            grepNoisePrefix(category)
            grepNoiseColor(category)
        }

        assertEquals("#", grepNoisePrefix(DemoTextMatchCategory.COMMENT))
        assertEquals("\"", grepNoisePrefix(DemoTextMatchCategory.STRING))
        assertEquals("✕", grepNoisePrefix(DemoTextMatchCategory.SUBSTRING))
        assertEquals(grepNoiseColor(DemoTextMatchCategory.COMMENT), grepNoiseColor(DemoTextMatchCategory.STRING))
        assertNotEquals(grepNoiseColor(DemoTextMatchCategory.SUBSTRING), grepNoiseColor(DemoTextMatchCategory.COMMENT))
    }
}
