package io.github.amichne.kast.server

import io.github.amichne.kast.api.client.ServerInstanceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json

class DescriptorStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun readDescriptors(daemonsFile: Path): List<ServerInstanceDescriptor> =
        json.decodeFromString(Files.readString(daemonsFile))

    @Test
    fun `writes and deletes descriptor via registry`() {
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = "/tmp/workspace",
            backendName = "headless",
            backendVersion = "0.1.0",
            socketPath = "/tmp/workspace/.kast/s",
        )
        val daemonsFile = tempDir.resolve("daemons.json")
        val daemonsPath = daemonsFile.toAbsolutePath().toString()
        val store = DescriptorStore(daemonsPath)

        store.write(descriptor)
        assertEquals(listOf(descriptor), readDescriptors(daemonsFile))

        store.delete(descriptor)
        assertFalse(daemonsFile.exists())
    }
}
