package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SemanticPathContentIdentityTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `streamed semantic input hashing observes cancellation`() {
        val input = root.resolve("large.jar")
        Files.write(input, ByteArray(DEFAULT_BUFFER_SIZE * 4) { index -> index.toByte() })
        val checks = AtomicInteger()

        assertThrows(ProcessCanceledException::class.java) {
            SemanticPathContentIdentity.file(input) { checks.incrementAndGet() > 2 }
        }
    }
}
