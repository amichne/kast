package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.ServerLimits
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendNewCapabilitiesTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `implementations returns concrete subtypes for an interface symbol`(): TestResult = runTest {
        val typeFile = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Greeter
                abstract class BaseGreeter : Greeter
                class LoudGreeter : BaseGreeter()
            """.trimIndent() + "\n",
        )
        val offset = Files.readString(typeFile).indexOf("Greeter")
        withBackend { backend ->
            val result = backend.implementations(
                ImplementationsQuery(
                    position = FilePosition(filePath = typeFile.toString(), offset = offset),
                ),
            )
            assertEquals("sample.Greeter", result.declaration.fqName)
            assertTrue(result.implementations.any { it.fqName == "sample.LoudGreeter" })
            assertTrue(result.implementations.none { it.fqName == "sample.BaseGreeter" })
        }
    }

    @Test
    fun `completions returns declarations available in file scope`(): TestResult = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "hi ${'$'}name"
                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val offset = Files.readString(file).indexOf("use")
        withBackend { backend ->
            val result = backend.completions(
                CompletionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                ),
            )
            assertTrue(result.items.any { it.name == "greet" })
        }
    }

    @Test
    fun `code actions returns structured response`(): TestResult = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "hi ${'$'}name"
            """.trimIndent() + "\n",
        )
        val offset = Files.readString(file).indexOf("greet")
        withBackend { backend ->
            val result = backend.codeActions(
                CodeActionsQuery(
                    position = FilePosition(filePath = file.toString(), offset = offset),
                ),
            )
            assertTrue(result.actions.isEmpty())
        }
    }

    private suspend fun withBackend(action: suspend (StandaloneAnalysisBackend) -> Unit) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { activeSession ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = activeSession,
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
}
