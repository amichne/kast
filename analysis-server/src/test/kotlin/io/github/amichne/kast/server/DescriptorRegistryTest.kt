package io.github.amichne.kast.server

import io.github.amichne.kast.api.ServerInstanceDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DescriptorRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `list and workspace lookup return stored descriptors`() {
        val store = DescriptorStore(tempDir)
        val workspaceRoot = tempDir.resolve("workspace")
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = workspaceRoot.toString(),
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = workspaceRoot.resolve(".kast/s").toString(),
        )
        store.write(descriptor)

        val registry = DescriptorRegistry(tempDir)
        val listed = registry.list()
        val filtered = registry.findByWorkspaceRoot(workspaceRoot)

        assertEquals(1, listed.size)
        assertEquals(descriptor, listed.single().descriptor)
        assertEquals(1, filtered.size)
        assertEquals(descriptor, filtered.single().descriptor)
    }
}
