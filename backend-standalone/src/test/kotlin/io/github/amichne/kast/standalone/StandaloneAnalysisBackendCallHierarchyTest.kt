package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CallHierarchyTruncationReason
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
    fun `outgoing hierarchy preserves duplicate call sites and depth semantics`() = runTest {
        val file = writeSampleSource()
        withBackend { backend ->
            val fileContent = Files.readString(file)
            val callerOffset = fileContent.indexOf("caller")

            val depthZeroResult = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = callerOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 0,
                ),
            )
            assertEquals(0, depthZeroResult.root.children.size)
            assertTrue(CallHierarchyTruncationReason.DEPTH_LIMIT in depthZeroResult.truncationReasons)

            val depthOneResult = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = callerOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 1,
                ),
            )
            assertEquals(listOf("sample.leaf", "sample.leaf", "sample.cycleA"), depthOneResult.root.children.map { it.symbol.fqName })
        }
    }

    @Test
    fun `incoming hierarchy orders children by call site and keeps duplicates`() = runTest {
        val file = writeSampleSource()
        withBackend { backend ->
            val fileContent = Files.readString(file)
            val leafOffset = fileContent.indexOf("leaf")

            val result = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = leafOffset),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                ),
            )

            assertEquals(
                listOf("sample.caller", "sample.caller", "sample.callerTwo"),
                result.root.children.map { it.symbol.fqName },
            )
        }
    }

    @Test
    fun `cycle and max total call truncation are surfaced explicitly`() = runTest {
        val file = writeSampleSource()
        withBackend { backend ->
            val fileContent = Files.readString(file)
            val cycleOffset = fileContent.indexOf("cycleA")
            val callerOffset = fileContent.indexOf("caller")

            val cycleResult = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = cycleOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 5,
                ),
            )
            assertTrue(CallHierarchyTruncationReason.CYCLE in cycleResult.truncationReasons)
            assertEquals("sample.cycleB", cycleResult.root.children.single().symbol.fqName)
            assertEquals("sample.cycleA", cycleResult.root.children.single().children.single().symbol.fqName)
            assertEquals(0, cycleResult.root.children.single().children.single().children.size)

            val maxCallsResult = backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = callerOffset),
                    direction = CallDirection.OUTGOING,
                    depth = 3,
                    maxTotalCalls = 1,
                ),
            )
            assertEquals(1, maxCallsResult.totalCalls)
            assertEquals(1, maxCallsResult.root.children.size)
            assertTrue(CallHierarchyTruncationReason.MAX_TOTAL_CALLS in maxCallsResult.truncationReasons)
        }
    }

    @Test
    fun `capabilities advertise call hierarchy`() = runTest {
        writeSampleSource()
        withBackend { backend ->
            val capabilities = backend.capabilities()
            assertTrue(ReadCapability.CALL_HIERARCHY in capabilities.readCapabilities)
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
            block(
                StandaloneAnalysisBackend(
                    workspaceRoot = workspaceRoot,
                    limits = ServerLimits(
                        maxResults = 100,
                        requestTimeoutMillis = 30_000,
                        maxConcurrentRequests = 4,
                    ),
                    session = session,
                ),
            )
        } finally {
            session.close()
        }
    }

    private fun writeSampleSource(): Path {
        val file = workspaceRoot.resolve("src/main/kotlin/sample/Calls.kt")
        Files.createDirectories(file.parent)
        file.writeText(
            """
            package sample

            fun leaf() {}

            fun cycleA() {
                cycleB()
            }

            fun cycleB() {
                cycleA()
            }

            fun caller() {
                leaf()
                leaf()
                cycleA()
            }

            fun callerTwo() {
                leaf()
            }
            """.trimIndent() + "\n",
        )
        return file
    }
}
