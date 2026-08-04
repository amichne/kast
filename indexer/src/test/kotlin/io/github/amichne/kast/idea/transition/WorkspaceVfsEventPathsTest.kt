package io.github.amichne.kast.idea.transition

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@TestApplication
class WorkspaceVfsEventPathsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `move exposes both old and new paths`() {
        val oldParent = root.resolve("old").also { Files.createDirectories(it) }
        val newParent = root.resolve("new").also { Files.createDirectories(it) }
        val file = oldParent.resolve("App.kt").also { Files.writeString(it, "fun app() = 1") }
        val local = LocalFileSystem.getInstance()
        val virtualFile = checkNotNull(local.refreshAndFindFileByNioFile(file))
        val virtualNewParent = checkNotNull(local.refreshAndFindFileByNioFile(newParent))

        val affected = VFileMoveEvent(this, virtualFile, virtualNewParent).affectedPaths().toList()

        assertEquals(listOf(file, newParent.resolve("App.kt")), affected)
    }

    @Test
    fun `rename exposes both old and new paths`() {
        val file = root.resolve("build.gradle.kts").also { Files.writeString(it, "plugins {}") }
        val virtualFile = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
        val affected = VFilePropertyChangeEvent(
            this,
            virtualFile,
            VirtualFile.PROP_NAME,
            "build.gradle.kts",
            "build.gradle.kts.disabled",
            false,
        ).affectedPaths().toList()

        assertEquals(listOf(file, root.resolve("build.gradle.kts.disabled")), affected)
    }
}
