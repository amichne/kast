package io.github.amichne.kast.server

import io.github.amichne.kast.api.DescriptorRegistry
import io.github.amichne.kast.api.ServerInstanceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class DescriptorStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes and deletes descriptor via registry`() {
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = "/tmp/workspace",
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = "/tmp/workspace/.kast/s",
        )
        val daemonsFile = tempDir.resolve("daemons.json")
        val store = DescriptorStore(daemonsFile)

        store.write(descriptor)
        val registry = DescriptorRegistry(daemonsFile)
        assertEquals(1, registry.list().size)

        store.delete(descriptor)
        assertEquals(0, registry.list().size)
        assertFalse(daemonsFile.exists())
    }
}
