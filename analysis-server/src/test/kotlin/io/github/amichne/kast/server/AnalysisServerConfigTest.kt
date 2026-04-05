package io.github.amichne.kast.server

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path

class AnalysisServerConfigTest {
    @Test
    fun `default socket path falls back to temp directory for long workspace roots`() {
        val workspaceRoot = Path(
            "/private/var/folders/test-root",
            "nested".repeat(12),
            "workspace".repeat(8),
        )

        val socketPath = defaultSocketPath(workspaceRoot)

        assertTrue(socketPath.toString().length <= 100)
        assertTrue(
            socketPath.startsWith(
                Path(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath()
                    .normalize(),
            ),
        )
        assertTrue(socketPath.fileName.toString().endsWith(".sock"))
    }
}
