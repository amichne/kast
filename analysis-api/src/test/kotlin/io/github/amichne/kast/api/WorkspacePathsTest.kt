package io.github.amichne.kast.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathsTest {

    @Nested
    inner class KastConfigHomeTest {
        @Test
        fun `resolves KAST_CONFIG_HOME when set`() {
            val env = mapOf("KAST_CONFIG_HOME" to "/custom/kast-config")
            val result = kastConfigHome(env::get)
            assertEquals(Path.of("/custom/kast-config").toAbsolutePath().normalize(), result)
        }

        @Test
        fun `falls back to XDG_CONFIG_HOME when KAST_CONFIG_HOME is absent`() {
            val env = mapOf("XDG_CONFIG_HOME" to "/custom/xdg")
            val result = kastConfigHome(env::get)
            assertEquals(Path.of("/custom/xdg/kast").toAbsolutePath().normalize(), result)
        }

        @Test
        fun `falls back to home dot config kast when both env vars are absent`() {
            val env = emptyMap<String, String>()
            val result = kastConfigHome(env::get)
            val expected = Path.of(System.getProperty("user.home"))
                .resolve(".config").resolve("kast")
                .toAbsolutePath().normalize()
            assertEquals(expected, result)
        }

        @Test
        fun `KAST_CONFIG_HOME takes priority over XDG_CONFIG_HOME`() {
            val env = mapOf(
                "KAST_CONFIG_HOME" to "/kast-specific",
                "XDG_CONFIG_HOME" to "/xdg-general",
            )
            val result = kastConfigHome(env::get)
            assertEquals(Path.of("/kast-specific").toAbsolutePath().normalize(), result)
        }
    }

    @Nested
    inner class DefaultDescriptorDirectoryTest {
        @Test
        fun `resolves to daemons subdirectory of config home`() {
            val env = mapOf("KAST_CONFIG_HOME" to "/custom/config")
            val result = defaultDescriptorDirectory(env::get)
            assertEquals(
                Path.of("/custom/config/daemons").toAbsolutePath().normalize(),
                result,
            )
        }
    }

    @Nested
    inner class KastLogDirectoryTest {
        @Test
        fun `resolves to logs subdirectory with workspace hash`() {
            val env = mapOf("KAST_CONFIG_HOME" to "/custom/config")
            val workspaceRoot = Path.of("/tmp/workspace")
            val result = kastLogDirectory(workspaceRoot, env::get)

            val normalizedRoot = workspaceRoot.toAbsolutePath().normalize().toString()
            val expectedHash = FileHashing.sha256(normalizedRoot).take(12)
            assertEquals(
                Path.of("/custom/config/logs/$expectedHash").toAbsolutePath().normalize(),
                result,
            )
        }

        @Test
        fun `different workspace roots produce different directories`() {
            val env = mapOf("KAST_CONFIG_HOME" to "/custom/config")
            val dir1 = kastLogDirectory(Path.of("/workspace/a"), env::get)
            val dir2 = kastLogDirectory(Path.of("/workspace/b"), env::get)
            assertTrue(dir1 != dir2)
        }
    }

    @Nested
    inner class LegacyBehaviorTest {
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
}
