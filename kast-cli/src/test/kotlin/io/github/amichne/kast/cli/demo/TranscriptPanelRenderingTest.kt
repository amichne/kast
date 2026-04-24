package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptPanelRenderingTest {
    @Test
    fun `transcript panel renders lines with tone prefixes inside box borders`() = testSession { terminal ->
        section {
            renderTranscriptPanel(
                title = "Live Transcript",
                panelContentWidth = 60,
                lines = listOf(
                    KotterDemoTranscriptLine("semantic references 45", KotterDemoStreamTone.CONFIRMED),
                    KotterDemoTranscriptLine(
                        "grep baseline 35 matches / 4 false positives",
                        KotterDemoStreamTone.ERROR,
                    ),
                ),
            )
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting().filter(String::isNotBlank)
        assertTrue(lines.any { it.contains("Live Transcript") }, "expected title: $lines")
        assertTrue(lines.any { it.contains("✓ semantic references 45") }, "expected confirmed line: $lines")
        assertTrue(lines.any { it.contains("✕ grep baseline 35 matches / 4 false positives") }, "expected error line: $lines")
    }

    @Test
    fun `transcript line with codePreview renders both path and code in stripped text`() = testSession { terminal ->
        section {
            renderTranscriptPanel(
                title = "Test",
                panelContentWidth = 80,
                lines = listOf(
                    KotterDemoTranscriptLine(
                        text = "SymbolWalker.kt:93",
                        tone = KotterDemoStreamTone.DETAIL,
                        codePreview = "when (val command = WalkerCommand.parse(raw)) {",
                    ),
                ),
            )
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting().filter(String::isNotBlank)
        val contentLine = lines.first { it.contains("SymbolWalker.kt:93") }
        assertTrue(contentLine.contains("SymbolWalker.kt:93"), "expected path")
        assertTrue(contentLine.contains("`when (val command = WalkerCommand.parse(raw)) {`"), "expected backtick-wrapped code: $contentLine")
    }

    @Test
    fun `transcript line without codePreview renders normally`() = testSession { terminal ->
        section {
            renderTranscriptPanel(
                title = "Test",
                panelContentWidth = 60,
                lines = listOf(
                    KotterDemoTranscriptLine("scope PROJECT exhaustive=true", KotterDemoStreamTone.CONFIRMED),
                ),
            )
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting().filter(String::isNotBlank)
        assertTrue(lines.any { it.contains("✓ scope PROJECT exhaustive=true") }, "expected normal rendering: $lines")
    }

    @Test
    fun `stream renderer includes codePreview in plain text output`() {
        val block = KotterDemoStreamBlock(
            entries = listOf(
                KotterDemoStreamEntry.Content(
                    text = "Walker.kt:93",
                    tone = KotterDemoStreamTone.DETAIL,
                    codePreview = "val x = parse(raw)",
                ),
            ),
        )
        val plainLines = streamLines(block)
        assertEquals(1, plainLines.size)
        assertTrue(plainLines[0].contains("Walker.kt:93"), "expected path in plain output")
        assertTrue(plainLines[0].contains("`val x = parse(raw)`"), "expected backtick-wrapped code in plain output: ${plainLines[0]}")
    }
}
