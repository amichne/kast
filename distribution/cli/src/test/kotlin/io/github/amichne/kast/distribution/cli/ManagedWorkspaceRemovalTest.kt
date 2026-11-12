package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedWorkspaceRemovalTest {
    @Test
    fun `removal requires retirement and preserves repository and external Gradle home`(@TempDir temporary: Path) {
        val (store, container) = removalWorkspace(temporary)
        assertThrows(ManagementRejected::class.java) { captureWorkspaceRemovals(store) {} }
        assertTrue(Files.exists(container.directory))
        assertEquals(Refinement.Refined(Unit), store.recordRetirement(container))
        val proofs = captureWorkspaceRemovals(store) {}
        val sentinel = Files.writeString(container.gradleHome.resolve("sentinel"), "external")
        Files.createSymbolicLink(container.home.resolve("external"), container.gradleHome)
        // Resume after an interrupted deletion without reconstructing a partially erased generation.
        Files.delete(container.configuration)
        removeWorkspaceProfiles(store, proofs)
        removeWorkspaceProfiles(store, proofs)
        assertFalse(Files.exists(store.containers))
        assertEquals("external", Files.readString(sentinel))
        assertTrue(Files.isDirectory(container.workspace))
        assertTrue(Files.isDirectory(container.runtime.root))
    }

    @Test
    fun `unproven mutation evidence and replaced directory keep workspace state`(@TempDir temporary: Path) {
        val (store, container) = removalWorkspace(temporary)
        assertEquals(Refinement.Refined(Unit), store.recordRetirement(container))
        val evidence = Files.createDirectories(container.home.resolve(".kast/unknown")).resolve("recovery")
        Files.writeString(evidence, "unresolved")
        assertThrows(ManagementRejected::class.java) { captureWorkspaceRemovals(store) {} }
        assertEquals("unresolved", Files.readString(evidence))
        Files.delete(evidence)
        Files.delete(evidence.parent)
        Files.delete(evidence.parent.parent)
        val proofs = captureWorkspaceRemovals(store) {}
        Files.move(container.directory, temporary.resolve("retained"))
        Files.createDirectory(container.directory, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
        val foreign = Files.writeString(container.directory.resolve("foreign"), "protected")
        assertThrows(ManagementRejected::class.java) { removeWorkspaceProfiles(store, proofs) }
        assertEquals("protected", Files.readString(foreign))
    }

    @Test
    fun `interrupted reclamation retains its proof while the root reappears`(@TempDir temporary: Path) {
        val (store, container) = removalWorkspace(temporary)
        assertEquals(Refinement.Refined(Unit), store.recordRetirement(container))
        val proof = captureWorkspaceRemovals(store) {}.single()
        val receipt = store.containers.resolve(".removing-${container.id.text}.json")
        writeContainerRecord(receipt, WorkspaceReclamation(container.workspace.toString(), proof))
        val detached = store.containers.resolve(".removing-${container.id.text}")
        Files.move(container.directory, detached)
        Files.delete(detached.resolve("config/environment"))
        reclaimVanishedWorkspaces(store)
        assertTrue(Files.exists(receipt))
        assertTrue(Files.exists(detached.resolve("container.json")))
        Files.delete(container.workspace)
        reclaimVanishedWorkspaces(store)
        assertFalse(Files.exists(receipt))
        assertFalse(Files.exists(detached))
        assertTrue(Files.isDirectory(container.runtime.root))
        assertTrue(Files.isDirectory(container.gradleHome))
        reclaimVanishedWorkspaces(store)
        assertFalse(Files.exists(container.directory))
    }
}

private fun removalWorkspace(temporary: Path): Pair<ContainerStore, AdmittedContainer> {
    val store =
        ContainerStore(
            temporary.toRealPath().resolve("store"),
            (ContainerPlatform.current() as Refinement.Refined).value,
        )
    val workspace = Files.createDirectory(temporary.resolve("project")).toRealPath()
    val gradle = Files.createDirectory(temporary.resolve("gradle")).toRealPath()
    val container =
        (store.create(
                ContainerCommand.Create("workspace", workspace.toString(), gradle.toString(), false),
                workspace,
                containerRuntimeFixture(store),
            ) as Refinement.Refined)
            .value
    return store to container
}
