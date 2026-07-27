package io.github.amichne.kast.api.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json

class DescriptorRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun descriptor(
        workspaceRoot: String = "/tmp/workspace",
        backendName: String = "headless",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = "0.1.0",
        socketPath = "/tmp/workspace/.kast/s",
        pid = pid,
    )

    private fun registry(daemonsFile: Path): DescriptorRegistry =
        DescriptorRegistry(daemonsFile.toAbsolutePath().toString())

    private fun readDescriptors(daemonsFile: Path): List<ServerInstanceDescriptor> =
        json.decodeFromString(Files.readString(daemonsFile))

    @Test
    fun `register persists a single descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)

        assertEquals(listOf(d), readDescriptors(daemonsFile))
    }

    @Test
    fun `register is idempotent for same workspace-backend-pid`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.register(d)
        assertEquals(listOf(d), readDescriptors(daemonsFile))
    }

    @Test
    fun `delete removes matching descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = registry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.delete(d)
        assertFalse(daemonsFile.exists())
    }
}
