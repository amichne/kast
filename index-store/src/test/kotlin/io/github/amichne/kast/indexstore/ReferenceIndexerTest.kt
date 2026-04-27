package io.github.amichne.kast.indexstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CancellationException

class ReferenceIndexerTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `populates symbol references from scanner output`() {
        val filePath = "/src/Greeter.kt"
        storeWithManifest(filePath).use { store ->
            ReferenceIndexer(store).indexReferences(listOf(filePath), referenceScanner = { path ->
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 20,
                        targetFqName = "kotlin.String",
                        targetPath = null,
                        targetOffset = null,
                    ),
                )
            })

            val refs = store.referencesToSymbol("kotlin.String")
            assertEquals(1, refs.size)
            assertEquals(filePath, refs.single().sourcePath)
            assertEquals(20, refs.single().sourceOffset)
        }
    }

    @Test
    fun `clears stale references before writing rescanned file`() {
        val filePath = "/src/Caller.kt"
        storeWithManifest(filePath).use { store ->
            store.upsertSymbolReference(
                sourcePath = filePath,
                sourceOffset = 5,
                targetFqName = "sample.staleTarget",
                targetPath = "/src/Stale.kt",
                targetOffset = 0,
            )

            ReferenceIndexer(store).indexReferences(listOf(filePath), referenceScanner = { path ->
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 28,
                        targetFqName = "sample.greet",
                        targetPath = "/src/Greeter.kt",
                        targetOffset = 15,
                    ),
                )
            })

            assertTrue(store.referencesToSymbol("sample.staleTarget").isEmpty())
            assertEquals(1, store.referencesToSymbol("sample.greet").size)
        }
    }

    @Test
    fun `survives scanner exception for one file without aborting batch`() {
        val filePaths = (0 until 5).map { i -> "/src/File$i.kt" }
        val failingPath = filePaths[2]
        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store).indexReferences(filePaths, referenceScanner = { path ->
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
            })

            val refs = store.referencesToSymbol("sample.target")
            assertEquals(4, refs.size)
            assertTrue(refs.none { it.sourcePath == failingPath })
        }
    }

    @Test
    fun `stops before writing scanned batch when cancelled`() {
        val filePaths = listOf("/src/File0.kt", "/src/File1.kt")
        var scans = 0
        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store, batchSize = 2).indexReferences(
                filePaths = filePaths,
                referenceScanner = { path ->
                    scans += 1
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "sample.target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
                isCancelled = { scans >= 1 },
            )

            assertTrue(store.referencesToSymbol("sample.target").isEmpty())
        }
    }

    @Test
    fun `propagates cancellation from scanner`() {
        val filePath = "/src/File.kt"
        storeWithManifest(filePath).use { store ->
            assertThrows(CancellationException::class.java) {
                ReferenceIndexer(store).indexReferences(listOf(filePath), referenceScanner = {
                    throw CancellationException("cancelled")
                })
            }
        }
    }

    @Test
    fun `reindexFiles replaces references for changed paths only`() {
        val filePaths = listOf("/src/A.kt", "/src/B.kt")
        storeWithManifest(*filePaths.toTypedArray()).use { store ->
            ReferenceIndexer(store).indexReferences(filePaths, referenceScanner = { path ->
                listOf(
                    SymbolReferenceRow(
                        sourcePath = path,
                        sourceOffset = 0,
                        targetFqName = "original.Target",
                        targetPath = null,
                        targetOffset = null,
                    ),
                )
            })
            assertEquals(2, store.referencesToSymbol("original.Target").size)

            ReferenceIndexer(store).reindexFiles(
                changedPaths = setOf("/src/A.kt"),
                referenceScanner = { path ->
                    listOf(
                        SymbolReferenceRow(
                            sourcePath = path,
                            sourceOffset = 0,
                            targetFqName = "updated.Target",
                            targetPath = null,
                            targetOffset = null,
                        ),
                    )
                },
            )

            assertEquals(1, store.referencesToSymbol("original.Target").size)
            assertEquals("/src/B.kt", store.referencesToSymbol("original.Target").single().sourcePath)
            assertEquals(1, store.referencesToSymbol("updated.Target").size)
            assertEquals("/src/A.kt", store.referencesToSymbol("updated.Target").single().sourcePath)
        }
    }

    private fun storeWithManifest(vararg filePaths: String): SqliteSourceIndexStore {
        val store = SqliteSourceIndexStore(workspaceRoot.toAbsolutePath().normalize())
        store.ensureSchema()
        store.saveManifest(filePaths.associateWith { 1L })
        return store
    }
}
