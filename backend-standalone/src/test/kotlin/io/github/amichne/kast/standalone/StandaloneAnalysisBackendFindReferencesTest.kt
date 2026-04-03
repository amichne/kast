package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
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

class StandaloneAnalysisBackendFindReferencesTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `find references returns cross-file usages and declaration metadata`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val firstUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val secondUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(firstUsageFile).indexOf("greet")
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = firstUsageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = true,
                ),
            )

            assertEquals("sample.greet", result.declaration?.fqName)
            assertEquals(normalizePath(declarationFile), result.declaration?.location?.filePath)
            assertEquals(
                listOf(normalizePath(secondUsageFile), normalizePath(firstUsageFile)),
                result.references.map { reference -> reference.filePath },
            )
            assertEquals(
                listOf("fun useAgain(): String = greet(\"again\")", "fun use(): String = greet(\"kast\")"),
                result.references.map { reference -> reference.preview },
            )
        }
    }

    @Test
    fun `capabilities advertise find references after implementation`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
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

            assertTrue(ReadCapability.FIND_REFERENCES in capabilities.readCapabilities)
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
