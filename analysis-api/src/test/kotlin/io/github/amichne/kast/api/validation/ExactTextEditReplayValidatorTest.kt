package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.TextEdit
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactTextEditReplayValidatorTest {
    @Test
    fun `replay preserves BOM mixed separators and non-BMP bytes around UTF-16 edits`() {
        val preimage = UTF8_BOM + "a\r\n😀old\nz\rtail".utf8()
        val postimage = UTF8_BOM + "a\r\n😀new\r\nline\nz\rtail".utf8()

        assertDoesNotThrow {
            ExactTextEditReplayValidator.requireExactPostimages(
                edits = listOf(edit(start = 4, end = 7, replacement = "new\nline")),
                images = listOf(image(preimage, postimage)),
            )
        }
    }

    @Test
    fun `replay rejects a claimed postimage with unrelated valid UTF-8 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExactTextEditReplayValidator.requireExactPostimages(
                edits = listOf(edit(start = 0, end = 5, replacement = "beta")),
                images = listOf(image("alpha".utf8(), "beta extra".utf8())),
            )
        }
    }

    @Test
    fun `replay rejects malformed UTF-8 in either exact image`() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)

        assertThrows(IllegalArgumentException::class.java) {
            ExactTextEditReplayValidator.requireExactPostimages(
                edits = listOf(edit(start = 0, end = 0, replacement = "x")),
                images = listOf(image(malformed, "x".utf8())),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactTextEditReplayValidator.requireExactPostimages(
                edits = listOf(edit(start = 0, end = 1, replacement = "x")),
                images = listOf(image("a".utf8(), malformed)),
            )
        }
    }

    @Test
    fun `replay rejects invalid UTF-16 ranges and surrogate splits`() {
        val invalidRanges = listOf(
            edit(start = -1, end = 0, replacement = "x"),
            edit(start = 3, end = 2, replacement = "x"),
            edit(start = 0, end = 6, replacement = "x"),
        )
        invalidRanges.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ExactTextEditReplayValidator.requireExactPostimages(
                    edits = listOf(invalid),
                    images = listOf(image("value".utf8(), "x".utf8())),
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            ExactTextEditReplayValidator.requireExactPostimages(
                edits = listOf(edit(start = 1, end = 2, replacement = "x")),
                images = listOf(image("😀value".utf8(), "x".utf8())),
            )
        }
    }

    @Test
    fun `replay rejects unsorted overlapping and duplicate zero-length edits`() {
        val invalidEditSets = listOf(
            listOf(edit(3, 4, "x"), edit(0, 1, "y")),
            listOf(edit(0, 3, "x"), edit(2, 4, "y")),
            listOf(edit(2, 2, "x"), edit(2, 2, "y")),
        )

        invalidEditSets.forEach { edits ->
            assertThrows(IllegalArgumentException::class.java) {
                ExactTextEditReplayValidator.requireExactPostimages(
                    edits = edits,
                    images = listOf(image("value".utf8(), "x".utf8())),
                )
            }
        }
    }

    @Test
    fun `replay rejects non-normalized or malformed replacement text`() {
        listOf("x\ry", "\uD800").forEach { replacement ->
            assertThrows(IllegalArgumentException::class.java) {
                ExactTextEditReplayValidator.requireExactPostimages(
                    edits = listOf(edit(start = 0, end = 1, replacement = replacement)),
                    images = listOf(image("a".utf8(), "x".utf8())),
                )
            }
        }
    }

    private fun edit(
        start: Int,
        end: Int,
        replacement: String,
    ): TextEdit = TextEdit(FILE_PATH, start, end, replacement)

    private fun image(preimage: ByteArray, postimage: ByteArray): ExactFileImage =
        ExactFileImage.of(FILE_PATH, preimage, postimage)

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private companion object {
        const val FILE_PATH: String = "/workspace/src/main/kotlin/sample/Example.kt"
        val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
