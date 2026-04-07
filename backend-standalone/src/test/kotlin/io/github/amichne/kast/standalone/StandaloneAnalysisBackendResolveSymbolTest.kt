package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.NormalizedPath
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.SymbolVisibility
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendResolveSymbolTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `resolve symbol returns declaration metadata for a function reference`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(usageFile).indexOf("greet")
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

            val result = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                ),
            )

            assertEquals("sample.greet", result.symbol.fqName)
            assertEquals(SymbolKind.FUNCTION, result.symbol.kind)
            assertEquals(normalizePath(declarationFile), result.symbol.location.filePath)
        }
    }

    @Test
    fun `resolve symbol returns PUBLIC visibility for default top-level function`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        withBackend { backend ->
            val result = backend.resolveSymbol(
                SymbolQuery(position = FilePosition(filePath = declarationFile.toString(), offset = queryOffset)),
            )

            assertEquals(SymbolVisibility.PUBLIC, result.symbol.visibility)
        }
    }

    @Test
    fun `resolve symbol returns PRIVATE visibility for private function`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                private fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        withBackend { backend ->
            val result = backend.resolveSymbol(
                SymbolQuery(position = FilePosition(filePath = declarationFile.toString(), offset = queryOffset)),
            )

            assertEquals(SymbolVisibility.PRIVATE, result.symbol.visibility)
        }
    }

    @Test
    fun `resolve symbol returns INTERNAL visibility for internal class`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                internal class Greeter(val name: String)
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("Greeter")
        withBackend { backend ->
            val result = backend.resolveSymbol(
                SymbolQuery(position = FilePosition(filePath = declarationFile.toString(), offset = queryOffset)),
            )

            assertEquals(SymbolVisibility.INTERNAL, result.symbol.visibility)
        }
    }

    @Test
    fun `resolve symbol returns LOCAL visibility for function-scoped declaration`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String {
                    val greeting = "hi $name"
                    return greeting
                }
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greeting")
        withBackend { backend ->
            val result = backend.resolveSymbol(
                SymbolQuery(position = FilePosition(filePath = declarationFile.toString(), offset = queryOffset)),
            )

            assertEquals(SymbolVisibility.LOCAL, result.symbol.visibility)
        }
    }

    @Test
    fun `resolve symbol returns PROTECTED visibility for protected member`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Base.kt",
            content = """
                package sample

                open class Base {
                    protected fun helper(): String = "help"
                }
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("helper")
        withBackend { backend ->
            val result = backend.resolveSymbol(
                SymbolQuery(position = FilePosition(filePath = declarationFile.toString(), offset = queryOffset)),
            )

            assertEquals(SymbolVisibility.PROTECTED, result.symbol.visibility)
        }
    }

    private suspend fun withBackend(action: suspend (StandaloneAnalysisBackend) -> Unit) {
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
            action(backend)
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
