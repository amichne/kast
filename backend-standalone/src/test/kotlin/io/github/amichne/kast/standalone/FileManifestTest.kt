package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import io.github.amichne.kast.standalone.cache.FileManifest
import io.github.amichne.kast.standalone.cache.scanTrackedKotlinFileTimestamps

class FileManifestTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `file manifest detects new files`() {
        val existingFile = writeSourceFile("sample/App.kt", "package sample\n\nfun welcome(): String = \"hi\"\n")
        val fileManifest = FileManifest(normalizeStandalonePath(workspaceRoot))
        fileManifest.save(scanTrackedKotlinFileTimestamps(sourceRoots()))

        val newFile = writeSourceFile("sample/NewFile.kt", "package sample\n\nfun salute(): String = \"hello\"\n")
        bumpLastModified(newFile)

        val snapshot = fileManifest.snapshot(sourceRoots())
        assertEquals(listOf(normalizeStandalonePath(newFile).toString()), snapshot.newPaths)
        assertTrue(snapshot.modifiedPaths.isEmpty())
        assertTrue(snapshot.deletedPaths.isEmpty())
        assertTrue(snapshot.currentPathsByLastModifiedMillis.containsKey(normalizeStandalonePath(existingFile).toString()))
    }

    @Test
    fun `file manifest detects deleted files`() {
        val file = writeSourceFile("sample/App.kt", "package sample\n\nfun welcome(): String = \"hi\"\n")
        val fileManifest = FileManifest(normalizeStandalonePath(workspaceRoot))
        fileManifest.save(scanTrackedKotlinFileTimestamps(sourceRoots()))

        Files.delete(file)

        val snapshot = fileManifest.snapshot(sourceRoots())
        assertEquals(listOf(normalizeStandalonePath(file).toString()), snapshot.deletedPaths)
        assertTrue(snapshot.newPaths.isEmpty())
        assertTrue(snapshot.modifiedPaths.isEmpty())
    }

    @Test
    fun `file manifest detects modified files`() {
        val file = writeSourceFile("sample/App.kt", "package sample\n\nfun welcome(): String = \"hi\"\n")
        val fileManifest = FileManifest(normalizeStandalonePath(workspaceRoot))
        fileManifest.save(scanTrackedKotlinFileTimestamps(sourceRoots()))

        file.writeText("package sample\n\nfun welcome(): String = \"hello\"\n")
        bumpLastModified(file)

        val snapshot = fileManifest.snapshot(sourceRoots())
        assertEquals(listOf(normalizeStandalonePath(file).toString()), snapshot.modifiedPaths)
        assertTrue(snapshot.newPaths.isEmpty())
        assertTrue(snapshot.deletedPaths.isEmpty())
    }

    @Test
    fun `file manifest scan narrows work to changed files`() {
        repeat(100) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = "package sample\n\nfun value$index(): Int = $index\n",
            )
        }
        val fileManifest = FileManifest(normalizeStandalonePath(workspaceRoot))
        fileManifest.save(scanTrackedKotlinFileTimestamps(sourceRoots()))

        val changedFile = workspaceRoot.resolve("src/main/kotlin/sample/File42.kt")
        changedFile.writeText("package sample\n\nfun renamedValue42(): Int = 42\n")
        bumpLastModified(changedFile)

        val snapshot = fileManifest.snapshot(sourceRoots())
        val fullTrackedPaths = scanTrackedKotlinFileTimestamps(sourceRoots()).keys

        assertEquals(1, snapshot.modifiedPaths.size)
        assertTrue(snapshot.modifiedPaths.size < fullTrackedPaths.size)
    }

    private fun sourceRoots(): List<Path> = listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFile(
        relativePath: String,
        content: String,
    ): Path {
        val file = workspaceRoot.resolve("src/main/kotlin").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }

    private fun bumpLastModified(file: Path) {
        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1_000),
        )
    }
}
