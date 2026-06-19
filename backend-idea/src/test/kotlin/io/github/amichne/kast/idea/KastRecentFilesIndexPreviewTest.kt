package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.SourceIndexSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastRecentFilesIndexPreviewTest {
    @Test
    fun `recent files render source index metadata by workspace relative path`() {
        val root = Path.of("/workspace").toAbsolutePath().normalize()
        val indexedFile = root.resolve("src/main/kotlin/app/App.kt")
        val snapshot = SourceIndexSnapshot(
            candidatePathsByIdentifier = mapOf(
                "App" to listOf(indexedFile.toString()),
                "main" to listOf(indexedFile.toString()),
            ),
            moduleNameByPath = mapOf(indexedFile.toString() to ":[main]"),
            packageByPath = mapOf(indexedFile.toString() to "app"),
            importsByPath = mapOf(indexedFile.toString() to listOf("kotlin.collections.List")),
            wildcardImportPackagesByPath = mapOf(indexedFile.toString() to listOf("kotlin.io")),
        )

        val rows = recentFileIndexRows(
            recentFilePaths = listOf(indexedFile),
            workspaceRoot = root,
            snapshot = snapshot,
        )

        assertEquals(1, rows.size)
        assertEquals("src/main/kotlin/app/App.kt", rows.single().displayPath)
        assertEquals(KastRecentFileIndexState.INDEXED, rows.single().state)
        assertEquals(":[main]", rows.single().moduleName)
        assertEquals("app", rows.single().packageName)
        assertEquals(2, rows.single().identifierCount)
        assertEquals(1, rows.single().importCount)
        assertEquals(1, rows.single().wildcardImportCount)
    }

    @Test
    fun `recent files stay visible when the source index is unavailable`() {
        val root = Path.of("/workspace").toAbsolutePath().normalize()
        val recentFile = root.resolve("src/test/kotlin/app/AppTest.kt")

        val rows = recentFileIndexRows(
            recentFilePaths = listOf(recentFile, recentFile),
            workspaceRoot = root,
            snapshot = null,
        )

        assertEquals(1, rows.size)
        assertEquals("src/test/kotlin/app/AppTest.kt", rows.single().displayPath)
        assertEquals(KastRecentFileIndexState.INDEX_UNAVAILABLE, rows.single().state)
        assertEquals(null, rows.single().identifierCount)
    }
}
