package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallNodeExpansion
import io.github.amichne.kast.api.FilePosition
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

class StandaloneAnalysisBackendCallHierarchyTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `depth zero returns only the selected declaration root`() = runTest {
        val sourceFile = writeFile(
            relativePath = "src/main/kotlin/sample/Calls.kt",
            content = """
                package sample

                fun root() {
                    leaf()
                }

                fun leaf() = Unit
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(sourceFile).indexOf("root")
        val session = session()
        try {
            val backend = backend(session)
            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = sourceFile.toString(), offset = queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 0,
                ),
            )

            assertEquals("sample.root", result.root.symbol.fqName)
            assertEquals(CallNodeExpansion.MAX_DEPTH, result.root.expansion)
            assertTrue(result.root.children.isEmpty())
            assertEquals(1, result.totalNodes)
            assertEquals(0, result.totalEdges)
        } finally {
            session.close()
        }
    }

    @Test
    fun `outgoing hierarchy keeps duplicate call sites and truncates cycles deterministically`() = runTest {
        val sourceFile = writeFile(
            relativePath = "src/main/kotlin/sample/Calls.kt",
            content = """
                package sample

                fun root() {
                    alpha()
                    alpha()
                    beta()
                }

                fun alpha() {
                    beta()
                }

                fun beta() {
                    alpha()
                }
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(sourceFile).indexOf("root")
        val session = session()
        try {
            val backend = backend(session)
            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = sourceFile.toString(), offset = queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 3,
                    maxTotalCalls = 100,
                ),
            )

            assertEquals(listOf("sample.alpha", "sample.alpha", "sample.beta"), result.root.children.map { it.symbol.fqName })
            assertEquals(
                listOf(4, 5, 6),
                result.root.children.map { it.callSite?.startLine },
            )
            val cycleTruncatedNodes = result.root.children
                .flatMap { child -> child.children }
                .flatMap { child -> child.children }
                .filter { node -> node.expansion == CallNodeExpansion.CYCLE_TRUNCATED }
            assertTrue(cycleTruncatedNodes.isNotEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun `capabilities advertise call hierarchy after implementation`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Calls.kt",
            content = """
                package sample

                fun root() = Unit
            """.trimIndent() + "\n",
        )
        val session = session()
        try {
            val backend = backend(session)
            val capabilities = backend.capabilities()
            assertTrue(ReadCapability.CALL_HIERARCHY in capabilities.readCapabilities)
        } finally {
            session.close()
        }
    }

    private fun session(): StandaloneAnalysisSession = StandaloneAnalysisSession(
        workspaceRoot = workspaceRoot,
        sourceRoots = emptyList(),
        classpathRoots = emptyList(),
        moduleName = "sources",
    )

    private fun backend(session: StandaloneAnalysisSession): StandaloneAnalysisBackend = StandaloneAnalysisBackend(
        workspaceRoot = workspaceRoot,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 4,
        ),
        session = session,
    )

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
