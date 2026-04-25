package io.github.amichne.kast.cli.demo

import io.github.amichne.kast.cli.DemoTextMatchCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DualTranscriptPanelRenderingTest {
    @Test
    fun `dual transcript panel keeps two bordered panes with one column gap`() {
        val lines = dualTranscriptPanelLines(
            leftHeader = """grep -rn "execute"""",
            leftLines = listOf(
                DualPaneLeftLine("A.kt:10 execute()", DemoTextMatchCategory.LIKELY_CORRECT),
                DualPaneLeftLine("B.kt:12 \"execute\"", DemoTextMatchCategory.STRING),
            ),
            rightHeader = "kast references --symbol execute",
            rightLines = listOf(KotterDemoTranscriptLine("A.kt:10 reference", KotterDemoStreamTone.CONFIRMED)),
            paneWidth = 58,
            paneHeight = 2,
            leftFooter = "⚑ 2 hits · 0 type info · 0 scope",
            rightFooter = "✓ 1 ref · typed · scoped · proven",
            gap = 1,
        )

        assertEquals(2 * 58 + 1, lines.first().length)
        assertEquals(' ', lines.first()[58])
        assertTrue(lines[1].contains("""grep -rn "execute""""))
        assertTrue(lines[1].contains("kast references --symbol execute"))
        assertTrue(lines.any { it.contains("? A.kt:10 execute()") })
        assertTrue(lines.any { it.contains("\" B.kt:12 \"execute\"") })
        assertTrue(lines.any { it.contains("✓ A.kt:10 reference") })
        assertTrue(lines.any { it.contains("⚑ 2 hits") && it.contains("✓ 1 ref") })
    }
}
