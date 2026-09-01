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
                    "--java-executable=/installed/idea-home/jbr/Contents/Home/bin/java",
                    "--idea-system-path=/private/cache/system",
                    "--idea-config-path=/private/cache/config",
                    "--idea-log-path=/private/cache/log",
                    "--private-plugins-path=/installed/private-plugins",
                    "--cache-state-path=/private/cache/cache-state",
                    "--workspace-root=/workspace",
                    "--socket-path=/runtime/kast.sock",
                    "--runtime-id=sha256:${"a".repeat(64)}",
                ),
            ),
        )
    }
}
