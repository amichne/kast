package io.github.amichne.kast.indexer.project

import io.github.amichne.kast.indexer.gradle.bootstrap.GradleProjectBootstrap
import io.github.amichne.kast.indexer.gradle.bootstrap.modelReadiness
import io.github.amichne.kast.indexer.gradle.bootstrap.projectStub
import io.github.amichne.kast.indexer.gradle.bootstrap.settlementEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class IndexerProjectIsolationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `project identity is Kast owned and source IDEA metadata is not consulted`() {
        val workspace = tempDir.resolve("workspace").toAbsolutePath().normalize()
        val storage = tempDir.resolve("kast-storage").toAbsolutePath().normalize()
        Files.createDirectory(workspace)
        val layout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = storage,
        )

        val task = ProjectOpener.openProjectTask(layout)

        assertEquals(layout.projectIdentityDirectory, task.projectRootDir)
        assertTrue(task.preventIprLookup)
        assertTrue(!layout.projectIdentityDirectory.startsWith(workspace))
        assertTrue(!layout.gradleProjectCacheDirectory.startsWith(workspace))
        layout.requireOwnedIdeaPaths(
            configDirectory = layout.ideaConfigDirectory,
            systemDirectory = layout.ideaSystemDirectory,
            logDirectory = layout.ideaLogDirectory,
            pluginsDirectory = layout.pluginsDirectory,
        )
    }

    @Test
    fun `project identity callback runs after open and before Gradle bootstrap`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val layout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = tempDir.resolve("kast-storage"),
        )
        val phases = mutableListOf<String>()
        val project = projectStub()
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = { observedProject, cache ->
                assertSame(project, observedProject)
                assertEquals(layout.gradleProjectCacheDirectory, cache)
                phases += "bootstrap"
            },
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { modelReadiness(moduleNames = listOf(":app")) },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> true },
        )
        val opener = ProjectOpener(
            gradleProjectBootstrap = bootstrap,
            openProjectIdentity = { projectIdentity, _ ->
                assertEquals(layout.projectIdentityDirectory, projectIdentity)
                phases += "open"
                project
            },
        )

        val opened = opener.openProject(layout.workspaceRoot, layout) { openedIdentity ->
            assertSame(project, openedIdentity)
            phases += "receipt"
        }

        assertSame(project, opened)
        assertEquals(listOf("open", "receipt", "bootstrap"), phases)
    }

    @Test
    fun `layout rejects writable storage inside the exact source root`() {
        val workspace = tempDir.resolve("workspace").toAbsolutePath().normalize()
        Files.createDirectory(workspace)

        assertThrows(IllegalArgumentException::class.java) {
            IndexerProjectLayout.create(
                workspaceRoot = workspace,
                storageRoot = workspace.resolve(".kast-indexer"),
            )
        }
    }

    @Test
    fun `layout rejects a writable child symlink into source`() {
        val workspace = tempDir.resolve("workspace")
        val storage = tempDir.resolve("storage")
        Files.createDirectory(workspace)
        Files.createDirectory(storage)
        Files.createSymbolicLink(storage.resolve("idea-system"), workspace)

        assertThrows(IllegalArgumentException::class.java) {
            IndexerProjectLayout.create(workspaceRoot = workspace, storageRoot = storage)
        }
    }

    @Test
    fun `layout rejects IDEA paths outside its canonical storage root`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)
        val layout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = tempDir.resolve("storage"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            layout.requireOwnedIdeaPaths(
                configDirectory = workspace,
                systemDirectory = layout.ideaSystemDirectory,
                logDirectory = layout.ideaLogDirectory,
                pluginsDirectory = layout.pluginsDirectory,
            )
        }
    }

    @Test
    fun `layout rejects admitted analysis storage inside source`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)

        assertThrows(IllegalArgumentException::class.java) {
            IndexerProjectLayout.create(
                workspaceRoot = workspace,
                storageRoot = tempDir.resolve("storage"),
                workspaceDataDirectory = workspace.resolve(".kast-analysis"),
            )
        }
    }

    @Test
    fun `bootstrap receipt binds token process and canonical roots`() {
        val workspace = tempDir.resolve("workspace")
        Files.createDirectory(workspace)
        val token = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        val layout = IndexerProjectLayout.create(
            workspaceRoot = workspace,
            storageRoot = tempDir.resolve("storage"),
            bootstrapToken = token,
        )

        layout.publishBootstrapReceipt()

        val receipt = Files.readString(layout.storageRoot.resolve("bootstrap/$token.json"))
        assertTrue(receipt.contains("\"token\":\"$token\""), receipt)
        assertTrue(receipt.contains("\"pid\":${ProcessHandle.current().pid()}"), receipt)
        assertTrue(receipt.contains(layout.workspaceRoot.toString()), receipt)
        assertTrue(receipt.contains(layout.storageRoot.toString()), receipt)
    }
}
