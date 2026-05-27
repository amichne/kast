package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.result.SearchMatch
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.writeText

class StandaloneAnalysisBackendWorkspaceSearchTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace search uses source index without loading all Kotlin files`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/A.kt",
            content = """
                package sample

                fun targetFunction(): String = "target"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/B.kt",
            content = """
                package sample

                fun callTarget(): String = targetFunction()
            """.trimIndent() + "\n",
        )

        withBackend { session, backend ->
            session.awaitInitialSourceIndex()
            assertFalse(session.isFullKtFileMapLoaded())
            assertTrue(session.candidateKotlinFilePaths("targetFunction").isNotEmpty())

            val result = backend.workspaceSearch(
                WorkspaceSearchQuery(
                    pattern = "targetFunction",
                    caseSensitive = true,
                ),
            )

            assertTrue(result.matches.any { match -> match.preview.contains("targetFunction") })
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `workspace search indexed and fallback results agree`() = runTest {
        createSearchFixture()
        val query = WorkspaceSearchQuery(
            pattern = "targetFunction",
            caseSensitive = true,
        )
        val indexedMatches = withBackend { session, backend ->
            session.awaitInitialSourceIndex()
            backend.workspaceSearch(query).matches
        }
        val unblockIndex = CountDownLatch(1)
        val fallbackMatches = withBackend(initialSourceIndexBuilder = {
            unblockIndex.await()
            emptyMap()
        }) { session, backend ->
            assertFalse(session.isInitialSourceIndexReady())
            backend.workspaceSearch(query).matches.also {
                unblockIndex.countDown()
            }
        }

        assertEquals(indexedMatches, fallbackMatches)
    }

    @Test
    fun `workspace search regex uses indexed workspace inventory when ready`() = runTest {
        createSearchFixture()

        withBackend { session, backend ->
            session.awaitInitialSourceIndex()
            assertFalse(session.isFullKtFileMapLoaded())

            val result = backend.workspaceSearch(
                WorkspaceSearchQuery(
                    pattern = "target(Function|Value)",
                    regex = true,
                    caseSensitive = true,
                ),
            )

            assertEquals(
                setOf(
                    "fun targetFunction(): String = targetValue",
                    "val targetValue = \"target\"",
                    "fun callTarget(): String = targetFunction()",
                ),
                result.matches.map(SearchMatch::preview).toSet(),
            )
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `workspace search falls back when source index is unavailable`() = runTest {
        createSearchFixture()
        val unblockIndex = CountDownLatch(1)

        withBackend(initialSourceIndexBuilder = {
            unblockIndex.await()
            emptyMap()
        }) { session, backend ->
            assertFalse(session.isInitialSourceIndexReady())

            val result = backend.workspaceSearch(
                WorkspaceSearchQuery(
                    pattern = "targetFunction",
                    caseSensitive = true,
                ),
            )

            assertTrue(result.matches.any { match -> match.preview.contains("targetFunction") })
            assertTrue(session.isFullKtFileMapLoaded())
            unblockIndex.countDown()
        }
    }

    private suspend fun withBackend(
        initialSourceIndexBuilder: (() -> Map<String, List<String>>)? = null,
        block: suspend (StandaloneAnalysisSession, StandaloneAnalysisBackend) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            initialSourceIndexBuilder = initialSourceIndexBuilder,
        )
        session.use {
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )
            block(session, backend)
        }
    }

    private fun writeFile(relativePath: String, content: String): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun createSearchFixture() {
        writeFile(
            relativePath = "src/main/kotlin/sample/A.kt",
            content = """
                package sample

                fun targetFunction(): String = targetValue
                val targetValue = "target"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/B.kt",
            content = """
                package sample

                fun callTarget(): String = targetFunction()
            """.trimIndent() + "\n",
        )
    }
}
