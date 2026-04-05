package io.github.amichne.kast.server

import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.api.workspaceMetadataDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class DescriptorStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes and deletes descriptor files`() {
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = "/tmp/workspace",
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = "/tmp/workspace/.kast/s",
        )
        val store = DescriptorStore(tempDir)

        val path = store.write(descriptor)
        assertTrue(path.exists())

        store.delete(descriptor)
        assertEquals(false, path.exists())
    }

    @Test
    fun `writing workspace-local descriptors adds a repo-local git exclude entry`() {
        val workspaceRoot = tempDir.resolve("workspace")
        Files.createDirectories(workspaceRoot.resolve(".git").resolve("info"))
        val descriptorDirectory = workspaceMetadataDirectory(workspaceRoot).resolve("instances")
        val descriptor = ServerInstanceDescriptor(
            workspaceRoot = workspaceRoot.toString(),
            backendName = "standalone",
            backendVersion = "0.1.0",
            socketPath = workspaceRoot.resolve(".kast/s").toString(),
        )
        val store = DescriptorStore(descriptorDirectory)

        store.write(descriptor)
        store.write(descriptor)

        val excludeContents = workspaceRoot.resolve(".git").resolve("info").resolve("exclude").readText()
        assertTrue(excludeContents.contains("# Kast local workspace metadata"))
        assertEquals(1, Regex("^/\\.kast/$", RegexOption.MULTILINE).findAll(excludeContents).count())
    }
}
