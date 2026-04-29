package io.github.amichne.kast.standalone

import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/**
 * Performance baselines for the SQLite-backed indexer pipeline.
 *
 * Tests are tagged `performance` so they can be excluded from the default CI run:
 *     ./gradlew :backend-standalone:test -PexcludeTags=performance
 * or run in isolation:
 *     ./gradlew :backend-standalone:test -PincludeTags=performance
 */
@Tag("performance")
class PerformanceBaselineTest {

    @TempDir
    lateinit var workspaceRoot: Path

    companion object {
        private const val INDEX_BUILD_500_MS = 5_000L
        private const val INCREMENTAL_10_OF_500_MS = 500L
        private const val CANDIDATE_RESOLUTION_P95_MS = 50L
        private const val SQLITE_ROUND_TRIP_500_MS = 2_000L
        private const val STARTUP_TO_READY_MS = 15_000L
        private const val CONCURRENT_QUERY_MAX_MS = 10_000L
        private const val TOLERANCE = 1.2
    }

    @Test
    fun `identifier index build time for 500 files`() {
        val fileCount = 500
        writeSourceFiles(fileCount)

        val elapsed = measureTimeMillis {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "perfmod",
                sourceIndexFileReader = { path -> Files.readString(path) },
            ).use { session ->
                session.awaitInitialSourceIndex()
            }
        }

        println("identifier_index_build_${fileCount}_files_ms: $elapsed")
        assertTrue(elapsed <= (INDEX_BUILD_500_MS * TOLERANCE).toLong()) {
            "Full index build took ${elapsed}ms, exceeds baseline ${INDEX_BUILD_500_MS}ms"
        }
    }

    @Test
    fun `incremental index update for 10 changed files`() {
        val totalFiles = 500
        val changedFiles = 10
        writeSourceFiles(totalFiles)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "perfmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
        ).use { session ->
            session.awaitInitialSourceIndex()

            repeat(changedFiles) { index ->
                writeSourceFile(
                    relativePath = "perf/File$index.kt",
                    content = """
                        package perf

                        fun modifiedValue$index(): Int = ${index + 1000}
                        fun extraFunction$index(): String = "changed"
                    """.trimIndent() + "\n",
                )
            }

            val elapsed = measureTimeMillis {
                session.refreshFiles(
                    (0 until changedFiles).map {
                        normalizeStandalonePath(
                            workspaceRoot.resolve("src/main/kotlin/perf/File$it.kt"),
                        ).toString()
                    }.toSet(),
                )
            }

            println("incremental_index_${changedFiles}_of_${totalFiles}_files_ms: $elapsed")
            assertTrue(elapsed <= (INCREMENTAL_10_OF_500_MS * TOLERANCE).toLong()) {
                "Incremental update took ${elapsed}ms, exceeds baseline ${INCREMENTAL_10_OF_500_MS}ms"
            }
        }
    }

    @Test
    fun `candidate file resolution time p95`() {
        val fileCount = 500
        val normalized = normalizeStandalonePath(workspaceRoot)

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val manifest = mutableMapOf<String, Long>()
            repeat(fileCount) { i ->
                val path = "/src/main/kotlin/perf/File$i.kt"
                manifest[path] = System.currentTimeMillis()
                store.saveFileIndex(
                    FileIndexUpdate(
                        path = path,
                        identifiers = setOf("value$i", "File$i", "call$i"),
                        packageName = "perf",
                        modulePath = null,
                        sourceSet = null,
                        imports = setOf("perf.File${(i + 1) % fileCount}"),
                        wildcardImports = emptySet(),
                    ),
                )
            }
            store.saveManifest(manifest)

            val index = MutableSourceIdentifierIndex.fromSourceIndexSnapshot(store.loadSourceIndexSnapshot())

            val iterations = 100
            val timings = mutableListOf<Long>()

            repeat(10) { index.candidatePathsFor("value42") }

            repeat(iterations) {
                val t = measureTimeMillis {
                    index.candidatePathsFor("value${it % fileCount}")
                }
                timings.add(t)
            }

            timings.sort()
            val p50 = timings[timings.size / 2]
            val p95 = timings[(timings.size * 0.95).toInt()]
            val p99 = timings[(timings.size * 0.99).toInt()]

            println("candidate_resolution_p50_ms: $p50, p95_ms: $p95, p99_ms: $p99")
            assertTrue(p95 <= (CANDIDATE_RESOLUTION_P95_MS * TOLERANCE).toLong()) {
                "Candidate resolution p95 was ${p95}ms, exceeds baseline ${CANDIDATE_RESOLUTION_P95_MS}ms"
            }
        }
    }

    @Test
    fun `SQLite round-trip save and load for 500 files`() {
        val fileCount = 500
        val normalized = normalizeStandalonePath(workspaceRoot)

        val elapsed = measureTimeMillis {
            SqliteSourceIndexStore(normalized).use { store ->
                store.ensureSchema()
                val manifest = mutableMapOf<String, Long>()
                repeat(fileCount) { i ->
                    val path = "/src/main/kotlin/perf/File$i.kt"
                    manifest[path] = System.currentTimeMillis()
                    store.saveFileIndex(
                        FileIndexUpdate(
                            path = path,
                            identifiers = (0..5).map { j -> "identifier${i}_$j" }.toSet(),
                            packageName = "perf",
                            modulePath = null,
                            sourceSet = null,
                            imports = (0..2).map { j -> "perf.Import${i}_$j" }.toSet(),
                            wildcardImports = setOf("perf.wildcard$i"),
                        ),
                    )
                }
                store.saveManifest(manifest)
            }

            SqliteSourceIndexStore(normalized).use { store ->
                val manifest = store.loadManifest()
                assertTrue(manifest != null && manifest.size == fileCount) {
                    "Expected $fileCount manifest entries, got ${manifest?.size}"
                }
                val snapshot = store.loadSourceIndexSnapshot()
                assertTrue(snapshot.candidatePathsByIdentifier["identifier0_0"].orEmpty().isNotEmpty()) {
                    "Expected non-empty identifier paths after reload"
                }
            }
        }

        println("sqlite_round_trip_${fileCount}_files_ms: $elapsed")
        assertTrue(elapsed <= (SQLITE_ROUND_TRIP_500_MS * TOLERANCE).toLong()) {
            "SQLite round-trip took ${elapsed}ms, exceeds baseline ${SQLITE_ROUND_TRIP_500_MS}ms"
        }
    }

    @Test
    fun `session startup to index ready time`() {
        writeSourceFiles(100)

        val elapsed = measureTimeMillis {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "perfmod",
                sourceIndexFileReader = { path -> Files.readString(path) },
            ).use { session ->
                session.awaitInitialSourceIndex()
                assertTrue(session.isInitialSourceIndexReady())
            }
        }

        println("startup_to_first_ready_ms: $elapsed")
        assertTrue(elapsed <= (STARTUP_TO_READY_MS * TOLERANCE).toLong()) {
            "Startup to ready took ${elapsed}ms, exceeds baseline ${STARTUP_TO_READY_MS}ms"
        }
    }

    @Test
    fun `concurrent queries during indexing do not block excessively`() {
        writeSourceFiles(500)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "perfmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
        ).use { session ->
            val executor = Executors.newFixedThreadPool(10)
            val futures = (0 until 10).map {
                CompletableFuture.supplyAsync(
                    {
                        measureTimeMillis {
                            session.sqliteStore.loadManifest()
                        }
                    },
                    executor,
                )
            }

            val timings = futures.map { it.get(CONCURRENT_QUERY_MAX_MS, TimeUnit.MILLISECONDS) }
            executor.shutdown()

            val maxQueryTime = timings.max()
            println("concurrent_query_max_ms: $maxQueryTime, all: $timings")
            assertTrue(maxQueryTime <= CONCURRENT_QUERY_MAX_MS) {
                "A concurrent query during indexing took ${maxQueryTime}ms, exceeds ${CONCURRENT_QUERY_MAX_MS}ms cap"
            }
        }
    }

    @Test
    fun `symbol reference lookup via SQLite`() {
        val fileCount = 100
        val refsPerFile = 20
        val normalized = normalizeStandalonePath(workspaceRoot)

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val manifest = mutableMapOf<String, Long>()
            repeat(fileCount) { fileIdx ->
                val sourcePath = "/src/main/kotlin/perf/File$fileIdx.kt"
                manifest[sourcePath] = System.currentTimeMillis()
                repeat(refsPerFile) { refIdx ->
                    store.upsertSymbolReference(
                        sourcePath = sourcePath,
                        sourceOffset = refIdx * 50,
                        targetFqName = "perf.TargetClass.targetFunction",
                        targetPath = "/src/main/kotlin/perf/Target.kt",
                        targetOffset = 100,
                    )
                }
            }
            store.saveManifest(manifest)

            val iterations = 100
            val timings = mutableListOf<Long>()
            repeat(iterations) {
                val t = measureTimeMillis {
                    store.referencesToSymbol("perf.TargetClass.targetFunction")
                }
                timings.add(t)
            }
            timings.sort()
            val p50 = timings[timings.size / 2]
            val p95 = timings[(timings.size * 0.95).toInt()]

            println("symbol_ref_lookup_p50_ms: $p50, p95_ms: $p95 (${fileCount * refsPerFile} refs)")
            assertTrue(p95 <= (CANDIDATE_RESOLUTION_P95_MS * TOLERANCE).toLong()) {
                "Symbol reference lookup p95 was ${p95}ms, exceeds ${CANDIDATE_RESOLUTION_P95_MS}ms"
            }
        }
    }

    @Test
    fun `candidate resolution uses memory index when available`() {
        writeSourceFiles(100)

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = sourceRoots(),
            classpathRoots = emptyList(),
            moduleName = "perfmod",
            sourceIndexFileReader = { path -> Files.readString(path) },
        ).use { session ->
            session.awaitInitialSourceIndex()

            val timings = mutableListOf<Long>()
            repeat(50) { i ->
                val t = measureTimeMillis {
                    session.candidateKotlinFilePaths("File${i % 100}", null)
                }
                timings.add(t)
            }
            timings.sort()
            val p95 = timings[(timings.size * 0.95).toInt()]

            println("candidate_resolution_with_index_p95_ms: $p95")
            assertTrue(p95 <= (CANDIDATE_RESOLUTION_P95_MS * TOLERANCE).toLong()) {
                "Candidate resolution with index p95 was ${p95}ms, exceeds ${CANDIDATE_RESOLUTION_P95_MS}ms"
            }
        }
    }

    @Test
    fun `non-blocking candidate resolution with zero wait timeout`() {
        writeSourceFiles(100)

        val elapsed = measureTimeMillis {
            StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = sourceRoots(),
                classpathRoots = emptyList(),
                moduleName = "perfmod",
                sourceIndexFileReader = { path ->
                    Thread.sleep(20)
                    Files.readString(path)
                },
                identifierIndexWaitMillis = 0,
            ).use { session ->
                // Query immediately — index definitely not ready with slow reader.
                repeat(10) { i ->
                    session.candidateKotlinFilePaths("File${i % 100}", null)
                }
            }
        }

        println("non_blocking_candidate_10_queries_ms: $elapsed")
        assertTrue(elapsed < STARTUP_TO_READY_MS) {
            "Non-blocking candidate queries took ${elapsed}ms, expected fast return"
        }
    }

    private fun sourceRoots(): List<Path> =
        listOf(normalizeStandalonePath(workspaceRoot.resolve("src/main/kotlin")))

    private fun writeSourceFiles(count: Int) {
        repeat(count) { index ->
            writeSourceFile(
                relativePath = "perf/File$index.kt",
                content = buildString {
                    appendLine("package perf")
                    appendLine()
                    appendLine("import perf.File${(index + 1) % count}")
                    appendLine()
                    appendLine("class File$index {")
                    appendLine("    fun value$index(): Int = $index")
                    appendLine("    fun call${index}() = File${(index + 1) % count}().value${(index + 1) % count}()")
                    appendLine("}")
                },
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
