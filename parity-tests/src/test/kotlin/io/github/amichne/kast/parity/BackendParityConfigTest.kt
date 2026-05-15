package io.github.amichne.kast.parity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackendParityConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun writesDeterministicTempConfigAndLoadsParityInputs() {
        val config = BackendParityConfig(
            standaloneSocket = tempDir.resolve("standalone-live.sock"),
            intellijSocket = tempDir.resolve("intellij-live.sock"),
            usageFile = tempDir.resolve("src").resolve("Usage.kt"),
            usageOffset = 37,
            brokenFile = tempDir.resolve("src").resolve("Broken.kt"),
        )

        val configFile = BackendParityConfigFixture.write(tempDir, config)
        val loaded = BackendParityConfigFixture.load(tempDir)

        assertEquals(tempDir.resolve("config.toml"), configFile)
        assertEquals(config, loaded)
        val expected = """
            [parity]
            standalone-socket = "STANDALONE_SOCKET"
            intellij-socket = "INTELLIJ_SOCKET"
            usage-file = "USAGE_FILE"
            usage-offset = 37
            broken-file = "BROKEN_FILE"
            """.trimIndent()
                           .replace("STANDALONE_SOCKET", config.standaloneSocket.toString())
                           .replace("INTELLIJ_SOCKET", config.intellijSocket.toString())
                           .replace("USAGE_FILE", config.usageFile.toString())
                           .replace("BROKEN_FILE", config.brokenFile.toString()) + "\n"
        assertEquals(expected, Files.readString(configFile))
    }

    @Test
    fun materializesDefaultFixturePathsInsideTempConfigHome() {
        val config = BackendParityConfigFixture.defaultConfig(tempDir)

        BackendParityConfigFixture.write(tempDir, config)
        val loaded = BackendParityConfigFixture.load(tempDir)

        assertEquals(tempDir.resolve("sockets").resolve("standalone.sock"), loaded.standaloneSocket)
        assertEquals(tempDir.resolve("sockets").resolve("intellij.sock"), loaded.intellijSocket)
        assertEquals(tempDir.resolve("fixtures").resolve("Usage.kt"), loaded.usageFile)
        assertEquals(tempDir.resolve("fixtures").resolve("Broken.kt"), loaded.brokenFile)
        assertEquals(config.usageOffset, loaded.usageOffset)
    }

    @Test
    fun missingConfigReturnsNull() {
        assertEquals(null, BackendParityConfigFixture.loadOrNull(tempDir))
    }

    @Test
    fun defaultFixtureWritesSourceFilesForParityQueries() {
        val configFile = BackendParityConfigFixture.materialize(tempDir, sourceConfigHome = tempDir.resolve("missing"))
        val loaded = BackendParityConfigFixture.load(tempDir)

        assertEquals(tempDir.resolve("config.toml"), configFile)
        val usageFile = requireNotNull(loaded.usageFile)
        val brokenFile = requireNotNull(loaded.brokenFile)
        assertTrue(Files.isRegularFile(usageFile))
        assertTrue(Files.isRegularFile(brokenFile))
        assertTrue(Files.readString(usageFile).contains("fun greeting"))
        assertTrue(Files.readString(brokenFile).contains("val broken"))
    }
}
