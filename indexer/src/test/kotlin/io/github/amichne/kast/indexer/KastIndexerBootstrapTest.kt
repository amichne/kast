package io.github.amichne.kast.indexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastIndexerBootstrapTest {
    @Test
    fun `launcher passes only admitted host arguments behind the IDEA command`() {
        assertEquals(
            listOf(
                KAST_INDEXER_COMMAND_NAME,
                "--workspace-root=/workspace",
                "--socket-path=/runtime/kast.sock",
                "--runtime-id=sha256:${"a".repeat(64)}",
            ),
            KastIndexerBootstrap.ideaMainArgs(
                arrayOf(
                    "--idea-home=/installed/idea-home",
                    "--workspace-root=/workspace",
                    "--socket-path=/runtime/kast.sock",
                    "--runtime-id=sha256:${"a".repeat(64)}",
                ),
            ),
        )
    }
}
