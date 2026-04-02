package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallNodeTruncationReason
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendCallHierarchyTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `depth zero returns only root node`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("greet()")

        withBackend { backend ->
            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = CallDirection.INCOMING,
                    depth = 0,
                ),
            )

            assertEquals("sample.greet", result.root.symbol.fqName)
            assertTrue(result.root.children.isEmpty())
            assertEquals(1, result.stats.totalNodes)
            assertEquals(0, result.stats.totalEdges)
        }
    }

    @Test
    fun `incoming hierarchy keeps duplicate call sites and stable ordering`() = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val firstCaller = writeFile(
            relativePath = "src/main/kotlin/sample/A.kt",
            content = """
                package sample

                fun alpha(): String = greet() + greet()
            """.trimIndent() + "\n",
        )
        val secondCaller = writeFile(
            relativePath = "src/main/kotlin/sample/B.kt",
            content = """
                package sample

                fun beta(): String = greet()
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")

        withBackend { backend ->
            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(declarationFile.toString(), queryOffset),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                    maxTotalCalls = 10,
                ),
            )

            assertEquals(3, result.root.children.size)
            assertEquals(
                listOf(firstCaller.toString(), firstCaller.toString(), secondCaller.toString()),
                result.root.children.map { child -> child.callSite?.filePath },
            )
            assertEquals(3, result.stats.totalEdges)
            val callSites = result.root.children.map { child -> child.callSite }
            assertNotNull(callSites[0])
            assertNotNull(callSites[1])
            assertTrue(checkNotNull(callSites[0]).startOffset < checkNotNull(callSites[1]).startOffset)
        }
    }

    @Test
    fun `outgoing hierarchy truncates cycles and advertises capability`() = runTest {
        val content = """
                package sample

                fun a(): String = b()
                fun b(): String = a()
            """.trimIndent() + "\n"
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Cycle.kt",
            content = content,
        )
        val queryOffset = content.indexOf("fun a") + "fun ".length

        withBackend { backend ->
            val capabilities = backend.capabilities()
            assertTrue(ReadCapability.CALL_HIERARCHY in capabilities.readCapabilities)

            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(file.toString(), queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 5,
                    maxTotalCalls = 10,
                ),
            )

            val outgoing = result.root.children.single()
            val recursiveBackEdge = outgoing.children.single()
            assertEquals("sample.b", outgoing.symbol.fqName)
            assertEquals("sample.a", recursiveBackEdge.symbol.fqName)
            assertEquals(CallNodeTruncationReason.CYCLE, recursiveBackEdge.truncation?.reason)
        }
    }

    private suspend fun withBackend(block: suspend (StandaloneAnalysisBackend) -> Unit) {
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
            block(backend)
        } finally {
            session.close()
        }
    }

    private fun writeFile(relativePath: String, content: String): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }
}
