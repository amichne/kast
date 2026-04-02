package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
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
    fun `outgoing hierarchy preserves repeated call-sites and respects depth`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Calls.kt",
            content = """
                package sample

                fun root(): String {
                    return helper() + helper()
                }

                fun helper(): String = leaf()

                fun leaf(): String = "ok"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("root")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = backend(session)

            val depthOne = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 1,
                ),
            )
            val depthTwo = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 2,
                ),
            )

            assertEquals("sample.root", depthOne.root.symbol.fqName)
            assertEquals(listOf("sample.helper", "sample.helper"), depthOne.root.children.map { child -> child.symbol.fqName })
            assertTrue(depthOne.root.children.all { child -> child.children.isEmpty() })
            assertEquals(listOf(listOf("sample.leaf"), listOf("sample.leaf")), depthTwo.root.children.map { it.children.map { c -> c.symbol.fqName } })
        } finally {
            session.close()
        }
    }

    @Test
    fun `incoming hierarchy truncates recursive cycles deterministically`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Recursive.kt",
            content = """
                package sample

                fun a() { b() }
                fun b() { a() }
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("fun a") + "fun ".length
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = backend(session)

            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    direction = CallDirection.INCOMING,
                    depth = 3,
                ),
            )

            assertEquals("sample.a", result.root.symbol.fqName)
            assertEquals(listOf("sample.b"), result.root.children.map { child -> child.symbol.fqName })
            assertEquals(listOf("sample.a"), result.root.children.single().children.map { child -> child.symbol.fqName })
            assertTrue(result.root.children.single().children.single().children.isEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun `call hierarchy boundaries cap total calls`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Bounded.kt",
            content = """
                package sample

                fun root() {
                    one()
                    two()
                    three()
                }

                fun one() {}
                fun two() {}
                fun three() {}
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(file).indexOf("root")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = backend(session)
            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 2,
                    maxTotalCalls = 2,
                ),
            )

            assertEquals(2, result.root.children.size)
            assertEquals(listOf("sample.one", "sample.two"), result.root.children.map { child -> child.symbol.fqName })
        } finally {
            session.close()
        }
    }

    @Test
    fun `capabilities advertise call hierarchy after implementation`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = backend(session)
            val capabilities = backend.capabilities()
            assertTrue(ReadCapability.CALL_HIERARCHY in capabilities.readCapabilities)
        } finally {
            session.close()
        }
    }

    private fun backend(session: StandaloneAnalysisSession) = StandaloneAnalysisBackend(
        workspaceRoot = workspaceRoot,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 4,
        ),
        session = session,
    )

    private fun writeFile(relativePath: String, content: String): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }
}
