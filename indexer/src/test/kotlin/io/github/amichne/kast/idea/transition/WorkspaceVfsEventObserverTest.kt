package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
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
        val refreshAuthority = CoordinatedVfsRefreshAuthority()
        val observer = WorkspaceVfsEventObserver.subscribe(
            project = projectFixture.get(),
            scope = WorkspaceVfsObservationScope(
                workspaceRoot = root,
                configurationFiles = emptySet(),
            ),
            refreshAuthority = refreshAuthority,
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

            observed.clear()
            refreshAuthority.runGlobalRefresh {
                projectFixture.get().messageBus
                    .syncPublisher(VirtualFileManager.VFS_CHANGES_BG)
                    .after(listOf(contentChange(dependencyLock, fromRefresh = true)))
            }
            assertEquals(emptyList<WorkspaceSignal>(), observed)

            refreshAuthority.runGlobalRefresh {
                projectFixture.get().messageBus
                    .syncPublisher(VirtualFileManager.VFS_CHANGES_BG)
                    .after(listOf(contentChange(dependencyLock)))
            }
            assertEquals(listOf(WorkspaceSignal.BuildSemantic), observed)

            observed.clear()
            refreshAuthority.runGlobalRefresh {
                projectFixture.get().messageBus
                    .syncPublisher(VirtualFileManager.VFS_CHANGES_BG)
                    .after(
                        listOf(
                            contentChange(dependencyLock, fromRefresh = true),
                            contentChange(dependencyLock),
                        ),
                    )
            }
            assertEquals(listOf(WorkspaceSignal.BuildSemantic), observed)

            observed.clear()
            projectFixture.get().messageBus
                .syncPublisher(VirtualFileManager.VFS_CHANGES_BG)
                .after(listOf(contentChange(dependencyLock, fromRefresh = true)))
            assertEquals(listOf(WorkspaceSignal.BuildSemantic), observed)
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

    private fun contentChange(
        file: com.intellij.openapi.vfs.VirtualFile,
        fromRefresh: Boolean = false,
    ): VFileContentChangeEvent =
        VFileContentChangeEvent(
            if (fromRefresh) VFileEvent.REFRESH_REQUESTOR else this,
            file,
            file.modificationStamp,
            file.modificationStamp + 1,
        )
}
