package io.github.amichne.kast.cli.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TranscriptLineCodePreviewTest {
    @Test
    fun `transcript line defaults codePreview to null`() {
        val line = KotterDemoTranscriptLine("some text", KotterDemoStreamTone.DETAIL)
        assertNull(line.codePreview)
    }

    @Test
    fun `transcript line carries codePreview when set`() {
        val line = KotterDemoTranscriptLine(
            text = "SymbolWalker.kt:93",
            tone = KotterDemoStreamTone.DETAIL,
            codePreview = "when (val command = WalkerCommand.parse(raw)) {",
        )
        assertEquals("SymbolWalker.kt:93", line.text)
        assertEquals("when (val command = WalkerCommand.parse(raw)) {", line.codePreview)
    }

    @Test
    fun `scenario event Line carries codePreview through to transcript line`() {
        val event = KotterDemoScenarioEvent.Line(
            atMillis = 100,
            phaseId = "search",
            text = "SymbolWalker.kt:93",
            tone = KotterDemoStreamTone.DETAIL,
            codePreview = "val x = parse(raw)",
        )
        assertEquals("val x = parse(raw)", event.codePreview)
    }

    @Test
    fun `scenario event Line defaults codePreview to null`() {
        val event = KotterDemoScenarioEvent.Line(
            atMillis = 100,
            phaseId = "search",
            text = "some text",
        )
        assertNull(event.codePreview)
    }
}
