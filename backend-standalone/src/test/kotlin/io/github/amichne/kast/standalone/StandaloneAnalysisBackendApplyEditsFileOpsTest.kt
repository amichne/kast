package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.api.FileOperation
import io.github.amichne.kast.api.FileHashing
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendApplyEditsFileOpsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `applyEdits with CreateFile refreshes workspace`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Seed.kt",
            content = """
                package sample

                class Seed
            """.trimIndent() + "\n",
        )
        val createdFile = workspaceRoot.resolve("src/main/kotlin/sample/NewType.kt")

        withBackend { backend, session ->
            val result = backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.CreateFile(
                            filePath = createdFile.toString(),
                            content = """
                                package sample

                                class NewType
                            """.trimIndent() + "\n",
                        ),
                    ),
                ),
            )

            assertEquals(listOf(createdFile.toString()), result.createdFiles)
            assertEquals(normalizePath(createdFile), session.findKtFile(createdFile.toString()).virtualFilePath)
        }
    }

    @Test
    fun `applyEdits with DeleteFile refreshes workspace`() = runTest {
        val deletedFile = writeFile(
            relativePath = "src/main/kotlin/sample/DeleteMe.kt",
            content = """
                package sample

                class DeleteMe
            """.trimIndent() + "\n",
        )

        withBackend { backend, session ->
            session.findKtFile(deletedFile.toString())

            val result = backend.applyEdits(
                ApplyEditsQuery(
                    edits = emptyList(),
                    fileHashes = emptyList(),
                    fileOperations = listOf(
                        FileOperation.DeleteFile(
                            filePath = deletedFile.toString(),
                            expectedHash = FileHashing.sha256(deletedFile.readText()),
                        ),
                    ),
                ),
            )

            assertEquals(listOf(deletedFile.toString()), result.deletedFiles)
            assertThrows<NotFoundException> {
                session.findKtFile(deletedFile.toString())
            }
        }
    }

    private suspend fun withBackend(
        block: suspend (StandaloneAnalysisBackend, StandaloneAnalysisSession) -> Unit,
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
            block(backend, session)
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

    private fun normalizePath(path: Path): String = NormalizedPath.of(path).value
}
