package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceStartupRootTest {
    @Test
    fun `legacy home registration cannot capture a repository thread`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val repository = Files.createDirectories(home.resolve("code/repository"))
        Files.writeString(repository.resolve("settings.gradle.kts"), "")
        val cwd = Files.createDirectories(repository.resolve("src"))
        val registry = WorkspaceEnrollmentStore(home.resolve("installation/config/workspaces.json"))
        registry.enroll(home)
        val enrollment = (registry.read() as EnrollmentRead.Read).enrollment

        val selected = enrollment.selectForStart(cwd.toString()) as WorkspaceSelection.Selected

        assertEquals(repository, selected.workspace.root.path)
        assertEquals(cwd, selected.workingDirectory.path)
        assertEquals(
            listOf(home, repository),
            (registry.snapshot() as WorkspaceRegistryRead.Read).snapshot.workspaces.map { it.root.path },
        )
    }

    @Test
    fun `fresh registration uses settings owner instead of invocation directory`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val repository = Files.createDirectory(root.resolve("repository"))
        Files.writeString(repository.resolve("settings.gradle.kts"), "")
        val cwd = Files.createDirectory(repository.resolve("src"))
        val registry = WorkspaceEnrollmentStore(root.resolve("config/workspaces.json"))
        val enrollment = (registry.read() as EnrollmentRead.Read).enrollment

        val selected = enrollment.selectForStart(cwd.toString()) as WorkspaceSelection.Selected

        assertEquals(repository, selected.workspace.root.path)
        assertEquals(
            listOf(repository),
            (registry.snapshot() as WorkspaceRegistryRead.Read).snapshot.workspaces.map { it.root.path },
        )
    }

    @Test
    fun `directory without settings cannot become an enrolled workspace`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val registry = WorkspaceEnrollmentStore(root.resolve("config/workspaces.json"))
        val enrollment = (registry.read() as EnrollmentRead.Read).enrollment

        assertInstanceOf(WorkspaceSelection.Rejected::class.java, enrollment.selectForStart(root.toString()))
        assertEquals(
            emptyList<WorkspaceRegistration>(),
            (registry.snapshot() as WorkspaceRegistryRead.Read).snapshot.workspaces,
        )
    }
}
