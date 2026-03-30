package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.DiagnosticSeverity
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
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
    fun `diagnostics report semantic errors from standalone analysis`() = runTest {
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
        try {
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
        } finally {
            session.close()
        }
    }

    @Test
    fun `capabilities advertise diagnostics after implementation`() = runTest {
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
        try {
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
        } finally {
            session.close()
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

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}
