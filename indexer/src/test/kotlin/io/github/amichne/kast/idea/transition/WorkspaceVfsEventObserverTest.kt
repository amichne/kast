package io.github.amichne.kast.idea.transition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class WorkspaceVfsEventObserverTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()
    }

    @TempDir
    lateinit var root: Path

    @Test
    fun `observer emits precise signals for semantic input events`() {
        val compilerSettings = virtualFile(root.resolve(".idea/compiler.xml"))
        val dependencyLock = virtualFile(root.resolve("gradle.lockfile"))
        val workspaceMetadata = virtualFile(root.resolve(".idea/workspace.xml"))
        val observed = mutableListOf<WorkspaceSignal>()
        val observer = WorkspaceVfsEventObserver.subscribe(
            project = projectFixture.get(),
            scope = WorkspaceVfsObservationScope(
                workspaceRoot = root,
                configurationFiles = emptySet(),
            ),
            observed = observed::add,
        )

        try {
            projectFixture.get().messageBus
                .syncPublisher(VirtualFileManager.VFS_CHANGES_BG)
                .after(
                    listOf(
                        contentChange(compilerSettings),
                        contentChange(dependencyLock),
                        contentChange(workspaceMetadata),
                    ),
                )

            assertEquals(
                listOf(WorkspaceSignal.SemanticEnvironment, WorkspaceSignal.BuildSemantic),
                observed,
            )
        } finally {
            observer.close()
        }
    }

    private fun virtualFile(path: Path) = path.also { file ->
        Files.createDirectories(file.parent)
        Files.writeString(file, "test")
    }.let { file ->
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
    }

    private fun contentChange(file: com.intellij.openapi.vfs.VirtualFile): VFileContentChangeEvent =
        VFileContentChangeEvent(this, file, file.modificationStamp, file.modificationStamp + 1)
}
