package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TextEdit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendImportOptimizeTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `optimizeImports returns edit plan for unused imports`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/other/Helper.kt",
            content = """
                package other

                class Helper
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/other/UnusedHelper.kt",
            content = """
                package other

                class UnusedHelper
            """.trimIndent() + "\n",
        )
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/OptimizeImports.kt",
            content = """
                package sample

                import other.Helper
                import other.UnusedHelper

                fun use(value: Helper): Helper = value
            """.trimIndent() + "\n",
        )

        withBackend { backend ->
            val result = backend.optimizeImports(
                ImportOptimizeQuery(
                    filePaths = listOf(file.toString()),
                ),
            )
            val updatedContent = applyEdit(file.readText(), result.edits.single())
            val normalizedFile = normalizePath(file)

            assertEquals(listOf(normalizedFile), result.affectedFiles.map(::normalizePath))
            assertEquals(1, result.edits.size)
            assertEquals(normalizedFile, normalizePath(result.fileHashes.single().filePath))
            assertTrue(updatedContent.contains("import other.Helper"))
            assertFalse(updatedContent.contains("import other.UnusedHelper"))
        }
    }

    private suspend fun withBackend(
        block: suspend (StandaloneAnalysisBackend) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )
            block(backend)
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun applyEdit(
        content: String,
        edit: TextEdit,
    ): String = buildString {
        append(content.substring(0, edit.startOffset))
        append(edit.newText)
        append(content.substring(edit.endOffset))
    }

    private fun normalizePath(path: Path): String = NormalizedPath.of(path).value

    private fun normalizePath(path: String): String = NormalizedPath.of(Path.of(path)).value
}
