package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendWorkspaceFilesTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace files capability is advertised`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val caps = backend.capabilities()
            assertTrue(caps.readCapabilities.contains(ReadCapability.WORKSPACE_FILES))
        }
    }

    @Test
    fun `workspace files returns module with discovered source root`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery())
            assertTrue(result.modules.isNotEmpty())
            assertEquals("sources", result.modules.first().name)
        }
    }

    @Test
    fun `workspace files file count matches written files`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery())
            val totalFiles = result.modules.sumOf { it.fileCount }
            assertEquals(2, totalFiles)
        }
    }

    @Test
    fun `workspace files with includeFiles returns file paths`() = runTest {
        val file = writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery(includeFiles = true))
            val allFiles = result.modules.flatMap { it.files }
            val normalizedFile = file.toRealPath().toString()
            assertTrue(allFiles.any { it == normalizedFile })
        }
    }

    @Test
    fun `workspace files without includeFiles returns empty file list`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery(includeFiles = false))
            assertTrue(result.modules.all { it.files.isEmpty() })
            assertTrue(result.modules.sumOf { it.fileCount } > 0)
        }
    }

    @Test
    fun `workspace files caps included files per module and reports truncation`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/C.kt", "package sample\n")

        withBackend { backend ->
            val result = backend.workspaceFiles(
                WorkspaceFilesQuery(
                    includeFiles = true,
                    maxFilesPerModule = 2,
                ),
            )
            val module = result.modules.first()

            assertEquals(3, module.fileCount)
            assertEquals(2, module.files.size)
            assertTrue(module.filesTruncated)
        }
    }

    @Test
    fun `workspace files defaults included file cap to server max results`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/C.kt", "package sample\n")

        withBackend(maxResults = 2) { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery(includeFiles = true))
            val module = result.modules.first()

            assertEquals(3, module.fileCount)
            assertEquals(2, module.files.size)
            assertTrue(module.filesTruncated)
        }
    }

    @Test
    fun `workspace files filtered by existing module name returns that module`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery(moduleName = "sources"))
            assertEquals(1, result.modules.size)
            assertEquals("sources", result.modules.first().name)
        }
    }

    @Test
    fun `workspace files filtered by nonexistent module returns empty`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        withBackend { backend ->
            val result = backend.workspaceFiles(WorkspaceFilesQuery(moduleName = "nonexistent"))
            assertTrue(result.modules.isEmpty())
        }
    }

    private suspend fun withBackend(
        maxResults: Int = 100,
        block: suspend (StandaloneAnalysisBackend) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = maxResults,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )
            block(backend)
        }
    }

    private fun writeFile(relativePath: String, content: String): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }
}
