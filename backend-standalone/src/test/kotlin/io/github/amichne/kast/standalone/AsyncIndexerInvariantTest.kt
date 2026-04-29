package io.github.amichne.kast.standalone

import io.github.amichne.kast.indexstore.SymbolReferenceRow
import io.github.amichne.kast.standalone.cache.SourceIndexCache
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
import java.util.concurrent.TimeUnit
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

    // ── Phase 2 tests ───────────────────────────────────────────────────

    @Test
    fun `phase 2 populates symbol_references from scanner output`() {
        val filePath = writeSourceFile(
            relativePath = "sample/Greeter.kt",
            content = "package sample\n\nfun greet(): String = \"hi\"\n",
        ).toString()
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(mapOf(filePath to System.currentTimeMillis()))

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.use {
            indexer.startPhase2 { path ->
                if (path == filePath) {
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 20,
                            targetFqName = "kotlin.String",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
        }

        val refs = store.referencesToSymbol("kotlin.String")
        assertEquals(1, refs.size)
        assertEquals(filePath, refs.single().sourcePath)
        assertEquals(20, refs.single().sourceOffset)
        cache.close()
    }

    @Test
    fun `phase 2 clears stale references before re-scanning a file`() {
        val filePath = writeSourceFile(
            relativePath = "sample/Caller.kt",
            content = "package sample\n\nfun call() = greet()\n",
        ).toString()
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(mapOf(filePath to System.currentTimeMillis()))

        // Pre-insert a stale reference
        store.upsertSymbolReference(
            sourcePath = filePath,
            sourceOffset = 5,
            targetFqName = "sample.staleTarget",
            targetPath = "/src/Stale.kt",
            targetOffset = 0,
        )
        assertEquals(1, store.referencesFromFile(filePath).size)

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.use {
            indexer.startPhase2 { path ->
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 28,
                        targetFqName = "sample.greet",
                        targetPath = "/src/Greeter.kt",
                        targetOffset = 15,
                    ),
                )
            }
            indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
        }

        val staleRefs = store.referencesToSymbol("sample.staleTarget")
        assertTrue(staleRefs.isEmpty(), "Stale reference should be cleared")

        val newRefs = store.referencesToSymbol("sample.greet")
        assertEquals(1, newRefs.size)
        assertEquals(filePath, newRefs.single().sourcePath)
        cache.close()
    }

    @Test
    fun `phase 2 scans only provided changed paths`() {
        val changedPath = writeSourceFile(
            relativePath = "sample/Changed.kt",
            content = "package sample\n\nfun changed() = stable()\n",
        ).toString()
        val stablePath = writeSourceFile(
            relativePath = "sample/Stable.kt",
            content = "package sample\n\nfun stable() = 1\n",
        ).toString()
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(mapOf(changedPath to System.currentTimeMillis(), stablePath to System.currentTimeMillis()))
        store.upsertSymbolReference(
            sourcePath = stablePath,
            sourceOffset = 1,
            targetFqName = "sample.previous",
            targetPath = null,
            targetOffset = null,
        )
        val scannedPaths = mutableListOf<String>()

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.use {
            indexer.startPhase2(
                changedPaths = setOf(changedPath),
                referenceScanner = { path ->
                    scannedPaths += path
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 10,
                            targetFqName = "sample.changedTarget",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )
            indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
        }

        assertEquals(listOf(changedPath), scannedPaths)
        assertEquals(1, store.referencesToSymbol("sample.changedTarget").size)
        assertEquals(1, store.referencesToSymbol("sample.previous").size)
        cache.close()
    }

    @Test
    fun `phase 2 uses configured reference batch size`() {
        val filePaths = (0 until 3).map { index ->
            writeSourceFile(
                relativePath = "sample/Batch$index.kt",
                content = "package sample\n\nfun batch$index() = $index\n",
            ).toString()
        }
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(filePaths.associateWith { System.currentTimeMillis() })

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
            referenceBatchSize = 1,
        )
        indexer.use {
            indexer.startPhase2 { path ->
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 0,
                        targetFqName = "sample.target",
                        targetPath = null,
                        targetOffset = null,
                    ),
                )
            }
            indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
        }

        assertEquals(3, store.referencesToSymbol("sample.target").size)
        cache.close()
    }

    @Test
    fun `phase 2 completes referenceIndexReady future on success`() {
        val filePath = writeSourceFile(
            relativePath = "sample/Empty.kt",
            content = "package sample\n",
        ).toString()
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(mapOf(filePath to System.currentTimeMillis()))

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.use {
            indexer.startPhase2 { emptyList() }
            assertDoesNotThrow {
                indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
            }
            assertTrue(indexer.referenceIndexReady.isDone)
        }
        cache.close()
    }

    @Test
    fun `phase 2 survives scanner exception on individual file without aborting`() {
        val filePaths = (0 until 5).map { i ->
            writeSourceFile(
                relativePath = "sample/File$i.kt",
                content = "package sample\n\nfun func$i() = $i\n",
            ).toString()
        }
        val failingPath = filePaths[2]

        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(filePaths.associateWith { System.currentTimeMillis() })

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.use {
            indexer.startPhase2 { path ->
                if (path == failingPath) {
                    throw RuntimeException("Simulated scanner failure")
                }
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 0,
                        targetFqName = "sample.target",
                        targetPath = null,
                        targetOffset = null,
                    ),
                )
            }
            indexer.referenceIndexReady.get(10, TimeUnit.SECONDS)
        }

        val refs = store.referencesToSymbol("sample.target")
        assertEquals(4, refs.size, "References from non-failing files should be present")
        assertTrue(refs.none { it.sourcePath == failingPath })
        cache.close()
    }

    @Test
    fun `phase 2 is cancellable via close without hanging`() {
        val filePaths = (0 until 200).map { i ->
            writeSourceFile(
                relativePath = "sample/File$i.kt",
                content = "package sample\n\nfun func$i() = $i\n",
            ).toString()
        }
        val normalized = normalizeStandalonePath(workspaceRoot)
        val cache = SourceIndexCache(normalized)
        val store = cache.store
        store.ensureSchema()
        store.saveManifest(filePaths.associateWith { System.currentTimeMillis() })

        val indexer = BackgroundIndexer(
            sourceRoots = sourceRoots(),
            sourceIndexFileReader = { Files.readString(it) },
            sourceModuleNameResolver = { null },
            sourceIndexCache = cache,
            store = store,
        )
        indexer.startPhase2 { _ ->
            Thread.sleep(500)
            emptyList()
        }

        assertTimeout(Duration.ofSeconds(5)) {
            indexer.close()
        }
        assertTrue(indexer.referenceIndexReady.isDone)
        cache.close()
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
