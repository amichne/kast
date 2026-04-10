package io.github.amichne.kast.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DescriptorRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private fun descriptor(
        workspaceRoot: String = "/tmp/workspace",
        backendName: String = "standalone",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = "0.1.0",
        socketPath = "/tmp/workspace/.kast/s",
        pid = pid,
    )

    @Test
    fun `list returns empty when daemons file does not exist`() {
        val registry = DescriptorRegistry(tempDir.resolve("daemons.json"))
        assertEquals(emptyList<RegisteredDescriptor>(), registry.list())
    }

    @Test
    fun `register and list round-trip a single descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        val listed = registry.list()

        assertEquals(1, listed.size)
        assertEquals(d, listed.single().descriptor)
    }

    @Test
    fun `register is idempotent for same workspace-backend-pid`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.register(d)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `delete removes matching descriptor`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        val d = descriptor()

        registry.register(d)
        registry.delete(d)
        assertTrue(registry.list().isEmpty())
        assertFalse(daemonsFile.exists())
    }

    @Test
    fun `findByWorkspaceRoot filters correctly`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        val d1 = descriptor(workspaceRoot = "/tmp/workspace-a")
        val d2 = descriptor(workspaceRoot = "/tmp/workspace-b")

        registry.register(d1)
        registry.register(d2)

        val found = registry.findByWorkspaceRoot(Path.of("/tmp/workspace-a"))
        assertEquals(1, found.size)
        assertEquals(d1, found.single().descriptor)
    }

    @Test
    fun `registered descriptor id is derived from workspace-backend-pid`() {
        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        val d = descriptor(workspaceRoot = "/tmp/ws", backendName = "standalone", pid = 99L)

        registry.register(d)
        val registered = registry.list().single()

        assertEquals("/tmp/ws:standalone:99", registered.id)
    }

    @Test
    fun `list and workspace lookup return stored descriptors (legacy compat)`() {
        val workspaceRoot = tempDir.resolve("workspace")
        val d = ServerInstanceDescriptor(
            workspaceRoot = workspaceRoot.toString(),
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = workspaceRoot.resolve(".kast/s").toString(),
        )

        val daemonsFile = tempDir.resolve("daemons.json")
        val registry = DescriptorRegistry(daemonsFile)
        registry.register(d)

        val listed = registry.list()
        val filtered = registry.findByWorkspaceRoot(workspaceRoot)

        assertEquals(1, listed.size)
        assertEquals(d, listed.single().descriptor)
        assertEquals(1, filtered.size)
        assertEquals(d, filtered.single().descriptor)
    }
}
