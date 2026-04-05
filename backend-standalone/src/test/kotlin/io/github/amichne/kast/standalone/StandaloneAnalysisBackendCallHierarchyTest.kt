package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallNodeTruncationReason
import io.github.amichne.kast.api.SCHEMA_VERSION
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
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
            assertEquals(0, result.stats.truncatedNodes)
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
                listOf(normalizePath(firstCaller), normalizePath(firstCaller), normalizePath(secondCaller)),
                result.root.children.map { child -> child.callSite?.filePath },
            )
            assertEquals(3, result.stats.totalEdges)
            val callSites = result.root.children.map { child -> checkNotNull(child.callSite) }
            assertTrue(callSites[0].startOffset < callSites[1].startOffset)
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

    @Test
    fun `incoming hierarchy uses indexed candidate files and reports bounded file visits`() = runTest {
        writeFile(
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
        writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        repeat(20) { index ->
            writeFile(
                relativePath = "src/main/kotlin/sample/unrelated/Unrelated$index.kt",
                content = """
                    package sample.unrelated

                    fun unrelated$index(): String = "value$index"
                """.trimIndent() + "\n",
            )
        }
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            session.awaitInitialSourceIndex()
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = defaultLimits(),
                session = session,
            )

            assertFalse(session.isFullKtFileMapLoaded())

            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                ),
            )

            assertFalse(session.isFullKtFileMapLoaded())
            assertEquals(3, result.stats.filesVisited)
        }
    }

    @Test
    fun `call hierarchy persists to git sha cache and reports cache hits`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        git("init")
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init")
        val queryOffset = Files.readString(file).indexOf("greet()")

        withBackend { backend ->
            val query = CallHierarchyQuery(
                position = FilePosition(file.toString(), queryOffset),
                direction = CallDirection.INCOMING,
                depth = 1,
                persistToGitShaCache = true,
            )

            val first = backend.callHierarchy(query)
            val second = backend.callHierarchy(query)

            val firstPersistence = checkNotNull(first.persistence)
            val secondPersistence = checkNotNull(second.persistence)
            assertFalse(firstPersistence.cacheHit)
            assertTrue(secondPersistence.cacheHit)
            assertTrue(Path.of(firstPersistence.cacheFilePath).exists())
            val cachePayload = Json.parseToJsonElement(
                Path.of(firstPersistence.cacheFilePath).readText(),
            ).jsonObject
            assertEquals(SCHEMA_VERSION, cachePayload.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertNotNull(cachePayload["root"]?.jsonObject)
            assertNotNull(cachePayload["stats"]?.jsonObject)
            assertEquals(first.root, second.root)
            assertEquals(first.stats, second.stats)
        }
    }

    private suspend fun withBackend(
        telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
        block: suspend (StandaloneAnalysisBackend) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        try {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = defaultLimits(),
                session = session,
                telemetry = telemetry,
            )
            block(backend)
        } finally {
            session.close()
        }
    }

    private fun defaultLimits(): ServerLimits = ServerLimits(
        maxResults = 100,
        requestTimeoutMillis = 30_000,
        maxConcurrentRequests = 4,
    )

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workspaceRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
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
