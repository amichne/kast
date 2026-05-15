package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendTypeHierarchyTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `type hierarchy depth zero returns only root`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                class Foo : Bar
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("Bar")

        withBackend { backend ->
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    depth = 0,
                ),
            )

            assertEquals("sample.Bar", result.root.symbol.fqName)
            assertTrue(result.root.children.isEmpty())
            assertEquals(1, result.stats.totalNodes)
            assertEquals(0, result.stats.maxDepthReached)
            assertFalse(result.stats.truncated)
        }
    }

    @Test
    fun `supertypes of class implementing interface returns interface fq name`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                class Foo : Bar
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("Foo")

        withBackend { backend ->
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = TypeHierarchyDirection.SUPERTYPES,
                    depth = 1,
                ),
            )

            assertEquals(listOf("sample.Bar"), result.root.symbol.supertypes)
            assertEquals(listOf("sample.Bar"), result.root.children.map { child -> child.symbol.fqName })
        }
    }

    @Test
    fun `subtypes of interface returns all implementors`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                class Foo : Bar
                class Baz : Bar
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("Bar")

        withBackend { backend ->
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = TypeHierarchyDirection.SUBTYPES,
                    depth = 1,
                ),
            )

            assertEquals(listOf("sample.Baz", "sample.Foo"), result.root.children.map { child -> child.symbol.fqName })
        }
    }

    @Test
    fun `type hierarchy BOTH direction walks both`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                open class Foo : Bar
                class Baz : Foo()
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("Foo")

        withBackend { backend ->
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = TypeHierarchyDirection.BOTH,
                    depth = 1,
                ),
            )

            assertEquals(listOf("sample.Bar", "sample.Baz"), result.root.children.map { child -> child.symbol.fqName })
        }
    }

    @Test
    fun `type hierarchy respects max results`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                class Alpha : Bar
                class Beta : Bar
                class Gamma : Bar
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("Bar")

        withBackend { backend ->
            val result = backend.typeHierarchy(
                TypeHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = TypeHierarchyDirection.SUBTYPES,
                    depth = 1,
                    maxResults = 3,
                ),
            )

            assertEquals(3, result.stats.totalNodes)
            assertTrue(result.stats.truncated)
            assertEquals(2, result.root.children.size)
        }
    }

    @Test
    fun `resolve symbol includes supertypes for class and null for function`() = runTest {
        val typesFile = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Bar
                class Foo : Bar
                fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val fooOffset = Files.readString(typesFile).indexOf("Foo")
        val greetOffset = Files.readString(typesFile).indexOf("greet")

        withBackend { backend ->
            val classResult = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(typesFile.toString(), fooOffset),
                ),
            )
            val functionResult = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(typesFile.toString(), greetOffset),
                ),
            )

            assertEquals(listOf("sample.Bar"), classResult.symbol.supertypes)
            assertNull(functionResult.symbol.supertypes)
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
}
