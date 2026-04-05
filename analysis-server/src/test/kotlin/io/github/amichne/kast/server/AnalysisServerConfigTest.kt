package io.github.amichne.kast.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.io.path.Path

class AnalysisServerConfigTest {
    @Test
    fun `loopback host without token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig() }
        assertDoesNotThrow { AnalysisServerConfig(host = "::1") }
        assertDoesNotThrow { AnalysisServerConfig(host = "localhost") }
        assertDoesNotThrow { AnalysisServerConfig(host = "LOCALHOST") }
    }

    @Test
    fun `loopback host with token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig(token = "secret") }
    }

    @Test
    fun `non-loopback host with token is accepted`() {
        assertDoesNotThrow { AnalysisServerConfig(host = "0.0.0.0", token = "secret") }
        assertDoesNotThrow { AnalysisServerConfig(host = "192.168.1.10", token = "t") }
    }

    @Test
    fun `non-loopback host without token is rejected`() {
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0") }
    }

    @Test
    fun `non-loopback host with blank token is rejected`() {
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0", token = "") }
        assertThrows<IllegalArgumentException> { AnalysisServerConfig(host = "0.0.0.0", token = "   ") }
    }

    @Test
    fun `default config binds to loopback`() {
        assertDoesNotThrow { AnalysisServerConfig() }
    }

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
