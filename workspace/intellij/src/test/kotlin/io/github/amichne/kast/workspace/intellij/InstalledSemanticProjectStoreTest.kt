package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledSemanticProjectStoreTest {
    @Test
    fun `each preparation creates one fresh empty store outside the workspace`(@TempDir root: Path) {
        val workspacePath = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val workspace = canonicalWorkspace(workspacePath)
        val state = Files.createDirectory(root.resolve("state")).toRealPath()

        val first = prepared(workspace, state)
        val second = prepared(workspace, state)

        assertNotEquals(first.path, second.path)
        listOf(first, second).forEach { store ->
            assertTrue(store.path.startsWith(state))
            assertFalse(store.path.startsWith(workspacePath))
            assertEquals(store.path.toString(), store.root.value)
            assertEquals(workspace, store.root.workspaceRoot)
            Files.list(store.path).use { entries -> assertEquals(0L, entries.count()) }
        }
    }

    @Test
    fun `runtime state inside the workspace fails before creating a store`(@TempDir root: Path) {
        val workspacePath = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val workspace = canonicalWorkspace(workspacePath)
        val state = Files.createDirectory(workspacePath.resolve("runtime-state")).toRealPath()

        val rejected = assertInstanceOf(
            InstalledSemanticProjectStorePreparation.Rejected::class.java,
            InstalledSemanticProjectStore.prepare(workspace, state),
        )

        assertEquals(InstalledSemanticProjectStoreFailure.OVERLAPS_WORKSPACE, rejected.failure)
        Files.list(state).use { entries -> assertEquals(0L, entries.count()) }
    }

    private fun prepared(
        workspace: CanonicalWorkspaceRoot,
        state: Path,
    ): InstalledSemanticProjectStore = assertInstanceOf(
        InstalledSemanticProjectStorePreparation.Prepared::class.java,
        InstalledSemanticProjectStore.prepare(workspace, state),
    ).store

    private fun canonicalWorkspace(path: Path): CanonicalWorkspaceRoot = when (
        val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(path)
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }
}
