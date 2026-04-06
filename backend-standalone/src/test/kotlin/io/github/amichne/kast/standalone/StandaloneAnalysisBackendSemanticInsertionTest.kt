package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SemanticInsertionTarget
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

class StandaloneAnalysisBackendSemanticInsertionTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `CLASS_BODY_END returns offset before closing brace`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Foo.kt",
            content = """
                package sample

                class Foo {
                }
            """.trimIndent() + "\n",
        )
        val content = file.readText()

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), content.indexOf("Foo")),
                    target = SemanticInsertionTarget.CLASS_BODY_END,
                ),
            )

            assertEquals(content.indexOf('}'), result.insertionOffset)
        }
    }

    @Test
    fun `CLASS_BODY_START returns offset after opening brace`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Foo.kt",
            content = """
                package sample

                class Foo {
                }
            """.trimIndent() + "\n",
        )
        val content = file.readText()

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), content.indexOf("Foo")),
                    target = SemanticInsertionTarget.CLASS_BODY_START,
                ),
            )

            assertEquals(content.indexOf('{') + 1, result.insertionOffset)
        }
    }

    @Test
    fun `AFTER_IMPORTS returns offset after last import`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Imports.kt",
            content = """
                package sample

                import kotlin.collections.List
                import kotlin.collections.Set
                import kotlin.collections.Map

                fun use() = Unit
            """.trimIndent() + "\n",
        )
        val content = file.readText()
        val importEnd = content.indexOf("import kotlin.collections.Map") + "import kotlin.collections.Map".length

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), content.indexOf("use")),
                    target = SemanticInsertionTarget.AFTER_IMPORTS,
                ),
            )

            assertEquals(offsetAfterLineBreak(content, importEnd), result.insertionOffset)
        }
    }

    @Test
    fun `AFTER_IMPORTS with no imports returns offset after package statement`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/PackageOnly.kt",
            content = """
                package sample

                fun use() = Unit
            """.trimIndent() + "\n",
        )
        val content = file.readText()
        val packageEnd = content.indexOf("package sample") + "package sample".length

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), content.indexOf("use")),
                    target = SemanticInsertionTarget.AFTER_IMPORTS,
                ),
            )

            assertEquals(offsetAfterLineBreak(content, packageEnd), result.insertionOffset)
        }
    }

    @Test
    fun `FILE_BOTTOM returns file length`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Bottom.kt",
            content = """
                package sample

                fun use() = Unit
            """.trimIndent() + "\n",
        )
        val content = file.readText()

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), 0),
                    target = SemanticInsertionTarget.FILE_BOTTOM,
                ),
            )

            assertEquals(content.length, result.insertionOffset)
        }
    }

    @Test
    fun `FILE_TOP returns zero`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Top.kt",
            content = """
                package sample

                fun use() = Unit
            """.trimIndent() + "\n",
        )

        withBackend { backend ->
            val result = backend.semanticInsertionPoint(
                SemanticInsertionQuery(
                    position = FilePosition(file.toString(), 0),
                    target = SemanticInsertionTarget.FILE_TOP,
                ),
            )

            assertEquals(0, result.insertionOffset)
        }
    }

    @Test
    fun `target is not a class for CLASS_BODY_END throws NotFoundException`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Function.kt",
            content = """
                package sample

                fun use() = Unit
            """.trimIndent() + "\n",
        )
        val content = file.readText()

        withBackend { backend ->
            assertThrows<NotFoundException> {
                backend.semanticInsertionPoint(
                    SemanticInsertionQuery(
                        position = FilePosition(file.toString(), content.indexOf("use")),
                        target = SemanticInsertionTarget.CLASS_BODY_END,
                    ),
                )
            }
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

    private fun offsetAfterLineBreak(
        content: String,
        offset: Int,
    ): Int {
        var cursor = offset
        if (content.getOrNull(cursor) == '\r') {
            cursor += 1
        }
        if (content.getOrNull(cursor) == '\n') {
            cursor += 1
        }
        return cursor
    }
}
