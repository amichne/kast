package io.github.amichne.kast.api.client

import io.github.amichne.kast.testing.inMemoryFileOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RED test proving DescriptorRegistry needs KastFileOperations injection.
 *
 * Context: Wave 1/2 established KastFileOperations abstraction with String wire protocol.
 * LocalDiskEditApplier successfully injected. DescriptorRegistry still uses direct Path/Files.
 *
 * Problem: DescriptorRegistry cannot be tested with Jimfs because:
 * 1. Constructor accepts Path but not KastFileOperations
 * 2. Internal operations use Path/Files directly
 * 3. No way to inject alternative filesystem implementation
 *
 * This RED test documents the desired API:
 * - DescriptorRegistry should accept KastFileOperations in constructor
 * - All file operations should go through fileOps abstraction (String paths)
 * - Jimfs-backed tests should work without touching real disk
 *
 * Current state: Tests will compile-fail because desired constructor doesn't exist yet.
 * Alternative: If uncommented workarounds allow compilation, tests pass but prove nothing
 * about abstraction boundary (Jimfs Path still works with java.nio.file.Files).
 *
 * GREEN implementation (Wave 3) will:
 * - Add KastFileOperations + daemonsPath:String constructor
 * - Convert internal operations to use fileOps.readText/writeText/exists/delete/createTempFile/moveAtomic
 * - Remove direct Path/Files usage
 */
class DescriptorRegistryJimfsTest {

    private fun descriptor(
        workspaceRoot: String = "/workspace",
        backendName: String = "standalone",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = "0.1.0",
        socketPath = "$workspaceRoot/.kast/s",
        pid = pid,
    )

    @Test
    fun `RED - registry should accept KastFileOperations and operate in Jimfs`() {
        // Arrange: Create isolated Jimfs filesystem
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        // RED: Constructor does not accept KastFileOperations - compile error
        // Desired API: DescriptorRegistry(daemonsPath: String, fileOps: KastFileOperations)
        val registry = DescriptorRegistry(
            daemonsPath = daemonsPath,
            fileOps = fixture.fileOps  // COMPILE ERROR: No such constructor parameter
        )

        val d1 = descriptor(workspaceRoot = "${fixture.root}workspace-a")
        val d2 = descriptor(workspaceRoot = "${fixture.root}workspace-b", pid = 43L)

        // Act: Register descriptors - should operate in memory via fileOps
        registry.register(d1)
        registry.register(d2)

        // Assert: Operations should have affected Jimfs, not disk
        val listed = registry.list()
        assertEquals(2, listed.size, "Registry should list descriptors from Jimfs")

        // Verify file exists in Jimfs memory, not on disk
        assertTrue(
            fixture.fileOps.exists(daemonsPath),
            "daemons.json should exist in Jimfs memory"
        )

        // Verify delete also works in memory
        registry.delete(d1)
        assertEquals(1, registry.list().size, "Delete should affect Jimfs state")
    }

    @Test
    fun `RED - empty registry in Jimfs should return empty list without disk access`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        // RED: Constructor missing fileOps parameter - compile error
        val registry = DescriptorRegistry(
            daemonsPath = daemonsPath,
            fileOps = fixture.fileOps  // COMPILE ERROR: No such constructor parameter
        )

        val listed = registry.list()
        assertEquals(
            emptyList<RegisteredDescriptor>(),
            listed,
            "Empty Jimfs registry should return empty list"
        )
    }

    @Test
    fun `RED - atomic writes should use fileOps abstraction with Jimfs`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        // RED: Constructor missing fileOps parameter - compile error
        val registry = DescriptorRegistry(
            daemonsPath = daemonsPath,
            fileOps = fixture.fileOps  // COMPILE ERROR: No such constructor parameter
        )

        val d = descriptor(workspaceRoot = "${fixture.root}workspace")

        // Act: Register should use fileOps.createTempFile/writeText/moveAtomic
        registry.register(d)

        // Assert: Final file should exist in Jimfs
        assertTrue(
            fixture.fileOps.exists(daemonsPath),
            "Atomic write should create file in Jimfs"
        )

        // Assert: No temp files should leak in Jimfs
        val parentDir = "${fixture.root}home/user/.kast"
        val files = fixture.fileOps.list(parentDir)
        assertEquals(
            1,
            files.size,
            "No temp files should leak after atomic write (only daemons.json)"
        )
    }

    @Test
    fun `RED - findByWorkspaceRoot should work with Jimfs paths through fileOps`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        // RED: Constructor missing fileOps parameter - compile error
        val registry = DescriptorRegistry(
            daemonsPath = daemonsPath,
            fileOps = fixture.fileOps  // COMPILE ERROR: No such constructor parameter
        )

        val workspace1 = "${fixture.root}workspace-a"
        val workspace2 = "${fixture.root}workspace-b"
        val d1 = descriptor(workspaceRoot = workspace1, backendName = "standalone")
        val d2 = descriptor(workspaceRoot = workspace2, backendName = "intellij")

        registry.register(d1)
        registry.register(d2)

        // Query by workspace using String path (no Path conversion needed)
        val found = registry.findByWorkspaceRoot(workspace1)
        assertEquals(1, found.size, "Should find single descriptor for workspace-a")
        assertEquals(d1, found.single().descriptor)
    }
}
