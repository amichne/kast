package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AsyncIndexerInvariantTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `indexer starts immediately on session creation`() {
        repeat(5) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun value$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path -> Files.readString(path) },
        ).use { session ->
            assertTimeout(Duration.ofSeconds(10)) {
                while (!session.isInitialSourceIndexReady()) {
                    Thread.sleep(50)
                }
            }
            assertTrue(session.isInitialSourceIndexReady())
        }
    }

    @Test
    fun `indexer completes a CompletableFuture when done`() {
        repeat(5) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun value$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path -> Files.readString(path) },
        ).use { session ->
            assertDoesNotThrow {
                assertTimeout(Duration.ofSeconds(10)) {
                    session.awaitInitialSourceIndex()
                }
            }
            assertTrue(session.isInitialSourceIndexReady())
        }
    }

    @Test
    fun `indexer can be cancelled via session close`() {
        repeat(100) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    class Type$index {
                        fun method$index(): String = "value$index"
                        fun helper$index(): Int = $index
                    }
                """.trimIndent() + "\n",
            )
        }

        assertTimeout(Duration.ofSeconds(5)) {
            val session = StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "sample",
                sourceIndexFileReader = { path -> Files.readString(path) },
            )
            session.close()
        }
    }

    @Test
    fun `indexer does not deadlock on close during indexing`() {
        repeat(200) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    class Generated$index {
                        fun compute$index(): Int = $index * 2
                        fun describe$index(): String = "Generated file $index"
                    }
                """.trimIndent() + "\n",
            )
        }

        assertTimeout(Duration.ofSeconds(5)) {
            val session = StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "sample",
                sourceIndexFileReader = { path ->
                    Thread.sleep(5)
                    Files.readString(path)
                },
            )
            session.close()
        }
    }

    @Test
    fun `indexer handles file not found gracefully during scan`() {
        val missingRelativePaths = setOf("sample/Missing0.kt", "sample/Missing1.kt")
        repeat(5) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun present$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }
        // Write and then delete files so they appear in the source root scan but are absent on read
        missingRelativePaths.forEach { relativePath ->
            val file = writeSourceFile(relativePath = relativePath, content = "package sample\n")
            Files.delete(file)
        }

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path ->
                val relative = sourceRoots().first().relativize(path).toString()
                if (missingRelativePaths.any { relative.endsWith(it) }) {
                    throw java.nio.file.NoSuchFileException(path.toString())
                }
                Files.readString(path)
            },
        ).use { session ->
            assertTimeout(Duration.ofSeconds(10)) {
                session.awaitInitialSourceIndex()
            }
            assertTrue(session.isInitialSourceIndexReady())
        }
    }

    @Test
    fun `concurrent queries during indexing return partial results`() {
        repeat(100) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun lookup$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path ->
                Thread.sleep(2)
                Files.readString(path)
            },
        ).use { session ->
            // Query the index before it is fully ready — should return partial results or empty, not throw
            val result: List<String> = assertDoesNotThrow(ThrowingSupplier {
                session.candidateKotlinFilePaths("lookup0")
            })
            // The result is either empty (not yet indexed) or contains the expected path
            assertTrue(result.isEmpty() || result.any { it.contains("File0.kt") })
        }
    }

    @Test
    fun `re-indexing after file change only processes changed files`() {
        repeat(10) { index ->
            writeSourceFile(
                relativePath = "sample/File$index.kt",
                content = """
                    package sample

                    fun value$index(): Int = $index
                """.trimIndent() + "\n",
            )
        }

        // First session: build the full index and persist the cache
        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexCacheSaveDelayMillis = 25,
        ).use { session ->
            session.awaitInitialSourceIndex()
        }

        // Modify exactly one file
        val changedFile = workspaceRoot.resolve("src/main/kotlin/sample/File4.kt")
        changedFile.writeText(
            """
                package sample

                fun renamedValue4(): Int = 4
            """.trimIndent() + "\n",
        )
        bumpLastModified(changedFile)

        // Second session: track how many files the reader touches
        val readCount = AtomicInteger(0)
        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "sample",
            sourceIndexFileReader = { path ->
                readCount.incrementAndGet()
                Files.readString(path)
            },
        ).use { session ->
            session.awaitInitialSourceIndex()
        }

        assertEquals(1, readCount.get(), "Only the changed file should be re-read on incremental startup")
    }

    // -- helpers --

    private fun sourceRoots(): List<Path> =
        listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFile(
        relativePath: String,
        content: String,
    ): Path {
        val file = workspaceRoot.resolve("src/main/kotlin").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }

    private fun bumpLastModified(file: Path) {
        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1_000),
        )
    }
}
