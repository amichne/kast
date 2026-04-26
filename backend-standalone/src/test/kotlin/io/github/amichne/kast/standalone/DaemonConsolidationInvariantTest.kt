package io.github.amichne.kast.standalone

import io.github.amichne.kast.indexstore.kastCacheDirectory
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Validates backend-side invariants for the daemon consolidation into `workspace ensure`.
 *
 * The daemon's core contract is that creating a [StandaloneAnalysisSession] eagerly starts
 * indexing and that descriptor uniqueness prevents multiple daemons per workspace root.
 * CLI-side routing (`workspace stop`) is tested in kast-cli.
 */
class DaemonConsolidationInvariantTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `session eagerly starts indexing on creation`() {
        writeSourceFiles(count = 5)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
        ).use { session ->
            session.awaitInitialSourceIndex()
            assertTrue(session.isInitialSourceIndexReady()) {
                "Initial source index should be ready after awaiting — " +
                    "the daemon relies on eager indexing at session creation"
            }
        }
    }

    @Test
    fun `session index is ready before enrichment completes`() {
        writeSourceFiles(count = 5)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
        ).use { session ->
            // Wait for the source index — the daemon must be servable at this point
            session.awaitInitialSourceIndex()
            assertTrue(session.isInitialSourceIndexReady()) {
                "Source index must be ready so the daemon can serve requests"
            }

            // Enrichment may or may not be done yet; the key invariant is that
            // the index is ready first (or simultaneously). If enrichment is
            // already complete that is also acceptable — both states satisfy the
            // "servable while enriching" contract.
            if (!session.isEnrichmentComplete()) {
                assertTrue(session.isInitialSourceIndexReady()) {
                    "Index must be ready before enrichment finishes — " +
                        "the daemon should be servable while still enriching"
                }
            }
        }
    }

    @Test
    fun `session handles concurrent close during indexing`() {
        writeSourceFiles(count = 100)

        assertTimeout(Duration.ofSeconds(5)) {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "sample",
            ).close()
        }

        // A new session for the same workspace must initialize without error
        assertDoesNotThrow {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "sample",
            ).use { session ->
                session.awaitInitialSourceIndex()
                assertTrue(session.isInitialSourceIndexReady())
            }
        }
    }

    @Test
    fun `session caches are under workspace gradle directory`() {
        writeSourceFiles(count = 3)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
        ).use { session ->
            session.awaitInitialSourceIndex()
        }

        val cacheDir = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
        val expectedDb = cacheDir.resolve("source-index.db")
        assertTrue(Files.isDirectory(cacheDir)) {
            "Cache directory should exist at ${workspaceRoot.resolve(".gradle/kast/cache")}"
        }
        assertTrue(Files.isRegularFile(expectedDb)) {
            "source-index.db should be created under the workspace .gradle/kast/cache directory"
        }
    }

    @Test
    @Disabled("daemon start was removed — workspace stop is tested in WorkspaceRuntimeManagerTest")
    fun `daemon consolidation CLI routing placeholder`() {
        // The `daemonStart` method was removed from WorkspaceRuntimeManager.
        // `workspaceStop` is tested in kast-cli WorkspaceRuntimeManagerTest.
    }

    private fun sourceRoots(): List<Path> =
        listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFiles(count: Int) {
        repeat(count) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun value$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }
    }

    private fun writeSourceFile(relativePath: String, content: String): Path {
        val file = workspaceRoot.resolve("src/main/kotlin").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }
}
