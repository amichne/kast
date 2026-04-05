package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathsTest {
    @Test
    fun `default descriptor directory lives under workspace metadata`() {
        assumeTrue(System.getenv("KAST_INSTANCE_DIR") == null)
        val workspaceRoot = Path.of("/tmp/workspace").toAbsolutePath().normalize()

        assertEquals(
            workspaceRoot.resolve(".kast").resolve("instances"),
            defaultDescriptorDirectory(workspaceRoot),
        )
    }

    @Test
    fun `workspace metadata directory lives under the workspace root`() {
        val workspaceRoot = Path.of("/tmp/workspace").toAbsolutePath().normalize()

        assertEquals(
            workspaceRoot.resolve(".kast"),
            workspaceMetadataDirectory(workspaceRoot),
        )
    }

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
