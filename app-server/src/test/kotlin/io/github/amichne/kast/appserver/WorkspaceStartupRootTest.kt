package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceStartupRootTest {
    @Test
    fun `nested repository keeps its nearest settings owner`(@TempDir temporary: Path) {
        for (marker in listOf("settings.gradle", "settings.gradle.kts")) {
            val outer = Files.createDirectory(temporary.resolve(marker)).toRealPath()
            Files.writeString(outer.resolve("settings.gradle.kts"), "")
            val repository = Files.createDirectory(outer.resolve("repository"))
            Files.createDirectory(repository.resolve(".git"))
            Files.writeString(repository.resolve(marker), "")
            val cwd = Files.createDirectory(repository.resolve("src"))
            val registry = WorkspaceEnrollmentStore(outer.resolve("config/workspaces.json"))
            val enrollment = (registry.read() as EnrollmentRead.Read).enrollment

            val selected = enrollment.selectForStart(cwd.toString()) as WorkspaceSelection.Selected

            assertEquals(repository, selected.workspace.root.path)
        }
    }

    @Test
    fun `settingsless nested repository cannot escape to ancestor`(@TempDir temporary: Path) {
        for (gitKind in listOf("directory", "worktree-file")) {
            val outer = Files.createDirectory(temporary.resolve(gitKind)).toRealPath()
            Files.writeString(outer.resolve("settings.gradle.kts"), "")
            val repository = Files.createDirectory(outer.resolve("repository"))
            val git = repository.resolve(".git")
            if (gitKind == "directory") Files.createDirectory(git) else Files.writeString(git, "gitdir: /other")
            val cwd = Files.createDirectory(repository.resolve("src"))

            assertEquals(
                CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND),
                FilesystemCanonicalRootDiscovery.discover(cwd),
            )
        }
    }

    @Test
    fun `invalid nearest settings cannot fall through to ancestor`(@TempDir temporary: Path) {
        for (marker in listOf("settings.gradle", "settings.gradle.kts")) {
            val outer = Files.createDirectory(temporary.resolve(marker)).toRealPath()
            Files.writeString(outer.resolve("settings.gradle.kts"), "")
            val repository = Files.createDirectory(outer.resolve("repository"))
            Files.createDirectory(repository.resolve(marker))

            assertEquals(
                CanonicalRootDiscovery.Rejected(CanonicalRootFailure.INVALID_ROOT_MARKER),
                FilesystemCanonicalRootDiscovery.discover(repository),
            )
        }
    }

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
