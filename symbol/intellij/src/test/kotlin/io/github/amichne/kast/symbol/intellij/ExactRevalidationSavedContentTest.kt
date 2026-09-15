package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactRevalidationSavedContentTest {
    @Test
    fun `saved committed flags cannot authorize a mismatched document or compiler text`() {
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CONTENT_UNCOMMITTED),
            admitExactSavedPsiText("fun saved() {}", "fun parsed() {}", "fun parsed() {}"),
        )
        assertEquals(
            Refinement.Rejected(ExactRevalidationRejection.CONTENT_UNCOMMITTED),
            admitExactSavedPsiText("fun saved() {}", "fun saved() {}", "fun document() {}"),
        )
        assertEquals(
            Refinement.Refined(Unit),
            admitExactSavedPsiText("fun saved() {}", "fun saved() {}", "fun saved() {}"),
        )
        assertEquals(Refinement.Refined(Unit), admitExactSavedPsiText("fun saved() {}", "fun saved() {}", null))
    }

    @Test
    fun `IntelliJ charset BOM and newline conversion matches compiler text exactly`() {
        for (charset in listOf(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            val raw = "\uFEFFclass Café\r\nfun f() {}\r".toByteArray(charset)
            val saved = LoadTextUtil.getTextByBinaryPresentation(raw, charset)
            assertEquals("class Café\nfun f() {}\n", saved.toString())
            assertEquals(
                Refinement.Refined(Unit),
                admitExactSavedPsiText(saved, "class Café\nfun f() {}\n", "class Café\nfun f() {}\n"),
            )
        }
    }

    @Test
    fun `length movement is finite and every actual byte including growth probe is charged`() {
        for ((actual, expected, charged) in listOf(Triple(5, 4, 5), Triple(3, 4, 3))) {
            var consumed = 0
            val result = readExactSavedBytes(ByteArrayInputStream(ByteArray(actual)), expected, { consumed += it }, {})
            assertEquals(ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CONTENT_CHANGED), result)
            assertEquals(charged, consumed)
        }
        var consumed = 0
        val maximum = IntellijExactRevalidationCapture.MAX_FILE_BYTES
        val growth = readExactSavedBytes(ByteArrayInputStream(ByteArray(maximum + 2)), maximum, { consumed += it }, {})
        assertEquals(ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CONTENT_CHANGED), growth)
        assertEquals(maximum + 1, consumed)
        var excludedReads = 0
        assertEquals(
            ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CAPACITY),
            readExactSavedBytes(ByteArrayInputStream(byteArrayOf(1)), maximum + 1, { excludedReads += it }, {}),
        )
        assertEquals(0, excludedReads)
    }

    @Test
    fun `exact bounded bytes survive and cancellation is never converted into capture success`() {
        val raw = byteArrayOf(1, 2, 3)
        var consumed = 0
        val read =
            readExactSavedBytes(ByteArrayInputStream(raw), raw.size, { consumed += it }, {}) as ExactSavedBytesRead.Read
        assertArrayEquals(raw, read.bytes)
        assertEquals(raw.size, consumed)
        val cancelled = java.util.concurrent.CancellationException()
        assertSame(
            cancelled,
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                readExactSavedBytes(ByteArrayInputStream(raw), raw.size, {}, { throw cancelled })
            },
        )
    }
}
