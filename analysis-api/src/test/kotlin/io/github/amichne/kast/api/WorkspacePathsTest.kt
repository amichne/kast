package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path

class WorkspacePathsTest {
    @TempDir
    lateinit var tempDir: Path


    @Nested
    inner class KastConfigHomeTest {
        @Test
        fun `resolves KAST_CONFIG_HOME when set`() {
            val configHome = tempDir.resolve("kast-config")
            val env = mapOf("KAST_CONFIG_HOME" to configHome.toString())
            val result = kastConfigHome(env::get)
            assertEquals(configHome.toAbsolutePath().normalize(), result)
        }

        @Test
        fun `falls back to XDG_CONFIG_HOME when KAST_CONFIG_HOME is absent`() {
            val xdgHome = tempDir.resolve("xdg")
            val env = mapOf("XDG_CONFIG_HOME" to xdgHome.toString())
            val result = kastConfigHome(env::get)
            assertEquals(xdgHome.resolve("kast").toAbsolutePath().normalize(), result)
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
            val configHome = tempDir.resolve("kast-specific")
            val env = mapOf(
                "KAST_CONFIG_HOME" to configHome.toString(),
                "XDG_CONFIG_HOME" to tempDir.resolve("xdg-general").toString(),
            )
            val result = kastConfigHome(env::get)
            assertEquals(configHome.toAbsolutePath().normalize(), result)
        }
    }

    @Nested
    inner class DefaultDescriptorDirectoryTest {
        @Test
        fun `resolves to daemons subdirectory of config home`() {
            val configHome = tempDir.resolve("config")
            val env = mapOf("KAST_CONFIG_HOME" to configHome.toString())
            val result = defaultDescriptorDirectory(env::get)
            assertEquals(
                configHome.resolve("daemons").toAbsolutePath().normalize(),
                result,
            )
        }
    }

    @Nested
    inner class KastLogDirectoryTest {
        @Test
        fun `resolves to logs under workspace data directory`() {
            val env = mapOf("KAST_CONFIG_HOME" to tempDir.resolve("config").toString())
            val workspaceRoot = Path.of("/tmp/workspace")
            val result = kastLogDirectory(workspaceRoot, env::get)

            assertEquals(
                workspaceDataDirectory(workspaceRoot, env::get).resolve("logs"),
                result,
            )
        }

        @Test
        fun `different workspace roots produce different directories`() {
            val env = mapOf("KAST_CONFIG_HOME" to tempDir.resolve("config").toString())
            val dir1 = kastLogDirectory(Path.of("/workspace/a"), env::get)
            val dir2 = kastLogDirectory(Path.of("/workspace/b"), env::get)
            assertTrue(dir1 != dir2)
        }
    }

    @Nested
    inner class LegacyBehaviorTest {
        @Test
        fun `workspace metadata directory resolves to workspace data directory`() {
            val workspaceRoot = Path.of("/tmp/workspace").toAbsolutePath().normalize()
            val env = mapOf("KAST_CONFIG_HOME" to tempDir.resolve("config").toString())
            assertEquals(
                workspaceDataDirectory(workspaceRoot, env::get),
                workspaceMetadataDirectory(workspaceRoot, env::get),
            )
        }

        @Test
        fun `default socket path stays short for long workspace data directories`() {
            val workspaceRoot = Path(
                "/private/var/folders/test-root",
                "nested".repeat(12),
                "workspace".repeat(8),
            )

            val socketPath = defaultSocketPath(workspaceRoot)
            assertTrue(socketPath.toString().length < 108)
        }
    }
}
