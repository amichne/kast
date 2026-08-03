package io.github.amichne.kast.idea.edit

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IdeaTextImagePlannerTest {
    @Test
    fun `LF image replaces normalized offsets exactly`() {
        val raw = "alpha\nbeta\n".utf8()

        val plan = plan(raw, "alpha\nbeta\n", edit(6, 10, "gamma"))

        assertArrayEquals("alpha\ngamma\n".utf8(), plan.resultBytes())
        assertEquals(IdeaLineSeparator.LF, plan.replacementLineSeparator)
    }

    @Test
    fun `CRLF image preserves untouched separators and uses CRLF for replacement newlines`() {
        val raw = "alpha\r\nbeta\r\n".utf8()

        val plan = plan(raw, "alpha\nbeta\n", edit(6, 10, "one\ntwo"))

        assertArrayEquals("alpha\r\none\r\ntwo\r\n".utf8(), plan.resultBytes())
        assertEquals(IdeaLineSeparator.CRLF, plan.replacementLineSeparator)
    }

    @Test
    fun `UTF-8 BOM and CRLF remain byte exact`() {
        val raw = UTF8_BOM + "alpha\r\nbeta\r\n".utf8()

        val plan = plan(raw, "alpha\nbeta\n", edit(6, 10, "gamma"))

        assertArrayEquals(UTF8_BOM + "alpha\r\ngamma\r\n".utf8(), plan.resultBytes())
    }

    @Test
    fun `mixed line separators remain exact outside the replacement`() {
        val raw = "a\r\nb\nc\rd".utf8()

        val plan = plan(raw, "a\nb\nc\nd", edit(2, 3, "B"))

        assertArrayEquals("a\r\nB\nc\rd".utf8(), plan.resultBytes())
    }

    @Test
    fun `non-BMP bytes remain exact around a UTF-16 edit`() {
        val raw = "😀old😀\r\n".utf8()

        val plan = plan(raw, "😀old😀\n", edit(2, 5, "new"))

        assertArrayEquals("😀new😀\r\n".utf8(), plan.resultBytes())
    }

    @Test
    fun `offset between surrogate halves is rejected`() {
        val failure = assertThrows(IdeaTextImagePlanningException::class.java) {
            plan("😀value".utf8(), "😀value", edit(1, 2, "x"))
        }

        assertEquals(IdeaTextImageLimitation.SURROGATE_SPLIT, failure.limitation)
    }

    @Test
    fun `normalized document mismatch is rejected`() {
        val failure = assertThrows(IdeaTextImagePlanningException::class.java) {
            plan("a\r\nb".utf8(), "a\r\nb", edit(0, 1, "A"))
        }

        assertEquals(IdeaTextImageLimitation.NORMALIZED_DOCUMENT_MISMATCH, failure.limitation)
    }

    @Test
    fun `explicit separator controls newline-bearing replacement when the source has none`() {
        val plan = IdeaTextImagePlanner.plan(
            rawPreimage = "value".utf8(),
            normalizedDocumentText = "value",
            edits = listOf(edit(0, 5, "first\nsecond")),
            replacementLineSeparator = IdeaLineSeparator.CRLF,
        )

        assertArrayEquals("first\r\nsecond".utf8(), plan.resultBytes())
    }

    @Test
    fun `duplicate zero-length edits at one UTF-16 offset are rejected`() {
        val failure = assertThrows(IdeaTextImagePlanningException::class.java) {
            plan("value".utf8(), "value", edit(2, 2, "a"), edit(2, 2, "b"))
        }

        assertEquals(IdeaTextImageLimitation.EDITS_NOT_SORTED_OR_OVERLAP, failure.limitation)
    }

    private fun plan(
        raw: ByteArray,
        normalized: String,
        vararg edits: IdeaNormalizedTextEdit,
    ): IdeaTextImagePlan = IdeaTextImagePlanner.plan(
        rawPreimage = raw,
        normalizedDocumentText = normalized,
        edits = edits.toList(),
    )

    private fun edit(start: Int, end: Int, replacement: String): IdeaNormalizedTextEdit =
        IdeaNormalizedTextEdit(
            startOffset = IdeaUtf16Offset(start),
            endOffset = IdeaUtf16Offset(end),
            replacementText = replacement,
        )

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private companion object {
        val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
