package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImageBase64
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.validation.FileHashing
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactFileImageTest {
    @Test
    fun `exact file image snapshots preimage and postimage bytes with their hashes`() {
        val preimage = "before\r\n".toByteArray()
        val postimage = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "after\r\n".toByteArray()

        val image = ExactFileImage.of(
            filePath = "/workspace/src/Sample.kt",
            preimageBytes = preimage,
            postimageBytes = postimage,
        )
        preimage.fill(0)
        postimage.fill(0)

        assertArrayEquals("before\r\n".toByteArray(), image.preimage.copyBytes())
        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "after\r\n".toByteArray(),
            image.postimage.copyBytes(),
        )
        assertEquals(FileHashing.sha256(image.preimage.copyBytes()), image.preimage.sha256.value)
        assertEquals(FileHashing.sha256(image.postimage.copyBytes()), image.postimage.sha256.value)
        assertNotSame(image.preimage.copyBytes(), image.preimage.copyBytes())

        val decoded = Json.decodeFromString<ExactFileImage>(Json.encodeToString(image))
        assertEquals(image, decoded)
        assertArrayEquals(image.postimage.copyBytes(), decoded.postimage.copyBytes())
    }

    @Test
    fun `exact byte image rejects a claimed hash that does not match its bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExactByteImage.of(
                bytes = "actual".toByteArray(),
                expectedSha256 = "0".repeat(64),
            )
        }
    }

    @Test
    fun `exact file image rejects relative and non-normalized paths`() {
        listOf("src/Sample.kt", "/workspace/src/../src/Sample.kt").forEach { invalidPath ->
            assertThrows(IllegalArgumentException::class.java) {
                ExactFileImage.of(
                    filePath = invalidPath,
                    preimageBytes = "before".toByteArray(),
                    postimageBytes = "after".toByteArray(),
                )
            }
        }
    }

    @Test
    fun `exact image query rejects an unnormalized path and mismatched result hash`() {
        val bytes = "after".toByteArray()
        val content = ExactFileImageBase64(Base64.getEncoder().encodeToString(bytes))
        val before = ExactFileImageSha256(FileHashing.sha256("before"))
        val after = ExactFileImageSha256(FileHashing.sha256(bytes))

        assertThrows(IllegalArgumentException::class.java) {
            ExactFileImageQuery(ExactFileImagePath("/workspace/src/../Sample.kt"), before, content, after)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactFileImageQuery(
                ExactFileImagePath("/workspace/Sample.kt"),
                before,
                content,
                ExactFileImageSha256("0".repeat(64)),
            )
        }
    }
}
