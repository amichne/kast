package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendDiagnosticsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `diagnostics report semantic errors from standalone analysis`(): TestResult = runTest {
        val brokenFile = writeFile(
            relativePath = "src/main/kotlin/sample/Broken.kt",
            content = """
                package sample

                fun broken(): String = missingValue()
            """.trimIndent() + "\n",
        )
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

            val result = backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(brokenFile.toString()),
                ),
            )

            assertTrue(result.diagnostics.isNotEmpty())
            assertEquals(DiagnosticSeverity.ERROR, result.diagnostics.first().severity)
            assertEquals("UNRESOLVED_REFERENCE", result.diagnostics.first().code)
            assertEquals(normalizePath(brokenFile), result.diagnostics.first().location.filePath)
            assertTrue(result.diagnostics.first().message.contains("Unresolved", ignoreCase = true))
        }
    }

    @Test
    fun `targeted refresh rebuilds analysis state for structural dependency changes`(): TestResult = runTest {
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val helperFile = workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt")
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

            val initialDiagnostics = backend.diagnostics(
                DiagnosticsQuery(
                    filePaths = listOf(usageFile.toString()),
                ),
            )

            assertTrue(initialDiagnostics.diagnostics.any { diagnostic -> diagnostic.code == "UNRESOLVED_REFERENCE" })

            Files.createDirectories(helperFile.parent)
            helperFile.writeText(
                """
                    package sample

                    fun greet(): String = "hi"
                """.trimIndent() + "\n",
            )

            val createdRefresh = backend.refresh(RefreshQuery(filePaths = listOf(helperFile.toString())))

            assertEquals(listOf(normalizePath(helperFile)), createdRefresh.refreshedFiles)
            assertTrue(
                backend.diagnostics(
                    DiagnosticsQuery(
                        filePaths = listOf(usageFile.toString()),
                    ),
                ).diagnostics.isEmpty(),
            )

            Files.delete(helperFile)

            val deletedRefresh = backend.refresh(RefreshQuery(filePaths = listOf(helperFile.toString())))

            assertEquals(listOf(normalizePath(helperFile)), deletedRefresh.removedFiles)
            assertTrue(
                backend.diagnostics(
                    DiagnosticsQuery(
                        filePaths = listOf(usageFile.toString()),
                    ),
                ).diagnostics.any { diagnostic -> diagnostic.code == "UNRESOLVED_REFERENCE" },
            )
        }
    }

    @Test
    fun `capabilities advertise diagnostics after implementation`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Broken.kt",
            content = """
                package sample

                fun broken(): String = missingValue()
            """.trimIndent() + "\n",
        )
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

            val capabilities = backend.capabilities()

            assertTrue(ReadCapability.DIAGNOSTICS in capabilities.readCapabilities)
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
