package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SourceIndexPageReadObserver
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.kastCacheDirectory
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SqliteSourceIndexReferenceQueryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `symbol references round-trip and clear by source file`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val otherPath = normalized.resolve("src/Other.kt").toString()
        val targetPath = normalized.resolve("src/Foo.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = callerPath,
                sourceOffset = 42,
                targetFqName = "lib.Foo",
                targetPath = targetPath,
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = otherPath,
                sourceOffset = 7,
                targetFqName = "lib.Foo",
                targetPath = targetPath,
                targetOffset = 10,
            )

            assertEquals(2, store.referencesToSymbol("lib.Foo").size)
            store.clearReferencesFromFile(callerPath)

            assertTrue(store.referencesFromFile(callerPath).isEmpty())
            assertEquals(1, store.referencesToSymbol("lib.Foo").size)
        }
    }

    @Test
    fun `reference replacement retains alias identity and rejects another source`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val canonicalDirectory = normalized.resolve("canonical").also(Files::createDirectories)
        val canonicalCaller = writeKotlinFile(canonicalDirectory.resolve("Caller.kt"))
        val aliasCaller = normalized.resolve("alias")
            .also { alias -> Files.createSymbolicLink(alias, canonicalDirectory) }
            .resolve(canonicalCaller.fileName)
        val otherCaller = writeKotlinFile(normalized.resolve("src/Other.kt"))
        val target = writeKotlinFile(normalized.resolve("src/Target.kt"))
        val expected = SymbolReferenceRow(
            sourcePath = workspaceSourceRawPath(normalized, canonicalCaller.toString()),
            sourceOffset = 12,
            targetFqName = "demo.Target",
            targetPath = workspaceSourceRawPath(normalized, target.toString()),
            targetOffset = 4,
        )
        val wrongSource = expected.copy(
            sourcePath = workspaceSourceRawPath(normalized, otherCaller.toString()),
            sourceOffset = 24,
        )

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()

            store.replaceReferencesFromFiles(
                listOf(aliasCaller.toString() to listOf(expected, wrongSource)),
            )

            assertEquals(listOf(expected), store.referencesFromFile(canonicalCaller.toString()))
            assertTrue(store.referencesFromFile(otherCaller.toString()).isEmpty())
        }
    }

    @Test
    fun `invalid retained source cannot authorize clearing every reference`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val outsidePath = normalized.parent.resolve("Outside.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = callerPath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = null,
                targetOffset = null,
            )

            assertThrows(IllegalArgumentException::class.java) {
                store.removeReferencesOutsideSources(listOf(outsidePath))
            }
            assertEquals(1, store.referencesFromFile(callerPath).size)
        }
    }

    @Test
    fun `symbol reference pages bound high cardinality lookup work and continue deterministically`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPaths = (0 until 500).map { index ->
            normalized.resolve("src/Caller${index.toString().padStart(3, '0')}.kt").toString()
        }
        val targetPath = normalized.resolve("src/HighCardinality.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            repeat(500) { index ->
                store.upsertSymbolReference(
                    sourcePath = callerPaths[index],
                    sourceOffset = index,
                    targetFqName = "lib.HighCardinality",
                    targetPath = targetPath,
                    targetOffset = 10,
                )
            }

            val first = store.generatedReferencePageToSymbol(
                targetFqName = "lib.HighCardinality",
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(4),
            )
            val second = store.generatedReferencePageToSymbol(
                targetFqName = "lib.HighCardinality",
                offset = requireNotNull(first.page.nextOffset),
                maxResults = PositiveInt(4),
            )

            assertEquals(4, first.page.references.size)
            assertEquals(NonNegativeInt(4), first.page.nextOffset)
            assertEquals(4, second.page.references.size)
            assertEquals(NonNegativeInt(8), second.page.nextOffset)
            assertEquals(first.generation, second.generation)
            assertTrue(first.page.references.toSet().intersect(second.page.references.toSet()).isEmpty())
            assertEquals(
                callerPaths.take(8).map { path -> workspaceSourceRawPath(normalized, path) },
                first.page.references.map { it.sourcePath } + second.page.references.map { it.sourcePath },
            )
        }
    }

    @Test
    fun `exact reference pages isolate declarations that share an fq name`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val firstCallerPath = normalized.resolve("src/FirstCaller.kt").toString()
        val secondCallerPath = normalized.resolve("src/SecondCaller.kt").toString()
        val targetPath = normalized.resolve("src/Target.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = firstCallerPath,
                sourceOffset = 1,
                targetFqName = "lib.overloaded",
                targetPath = targetPath,
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = secondCallerPath,
                sourceOffset = 2,
                targetFqName = "lib.overloaded",
                targetPath = targetPath,
                targetOffset = 40,
            )

            val page = store.generatedReferencePageToExactSymbol(
                target = ExactReferenceTarget(
                    fqName = "lib.overloaded",
                    declarationFile = NormalizedPath.parse(targetPath),
                    declarationStartOffset = NonNegativeInt(40),
                ),
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(10),
            )

            assertEquals(
                listOf(workspaceSourceRawPath(normalized, secondCallerPath)),
                page.page.references.map { it.sourcePath },
            )
            assertTrue(page.page.references.all { it.targetOffset == 40 })
        }
    }

    @Test
    fun `exact reference pages reject rows without declaration identity`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val legacyCallerPath = normalized.resolve("src/LegacyCaller.kt").toString()
        val targetPath = normalized.resolve("src/Target.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = legacyCallerPath,
                sourceOffset = 1,
                targetFqName = "lib.Target",
                targetPath = null,
                targetOffset = null,
            )

            val page = store.generatedReferencePageToExactSymbol(
                target = ExactReferenceTarget(
                    fqName = "lib.Target",
                    declarationFile = NormalizedPath.parse(targetPath),
                    declarationStartOffset = NonNegativeInt(10),
                ),
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(10),
            )

            assertFalse(page.exactIdentityAvailable)
            assertTrue(page.page.references.isEmpty())
        }
    }

    @Test
    fun `reference generation and rows share one database snapshot across store connections`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val beforePath = normalized.resolve("src/Before.kt").toString()
        val afterPath = normalized.resolve("src/After.kt").toString()
        val targetPath = normalized.resolve("src/Target.kt").toString()
        val generationRead = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val result = AtomicReference<io.github.amichne.kast.indexstore.api.reference.GeneratedSymbolReferencePage>()
        SqliteSourceIndexStore(normalized).use { writer ->
            writer.ensureSchema()
            writer.upsertSymbolReference(
                sourcePath = beforePath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = targetPath,
                targetOffset = 1,
            )
            val generationBeforeMutation = writer.readGeneration()
            SqliteSourceIndexStore(
                workspaceRoot = normalized,
                pageReadObserver = SourceIndexPageReadObserver {
                    generationRead.countDown()
                    assertTrue(writerCommitted.await(10, TimeUnit.SECONDS))
                },
                access = io.github.amichne.kast.indexstore.store.SqliteSourceIndexStoreAccess.READ_ONLY,
            ).use { reader ->
                val readThread = thread(name = "source-index-snapshot-reader") {
                    result.set(
                        reader.generatedReferencePageToSymbol(
                            targetFqName = "demo.Target",
                            offset = NonNegativeInt(0),
                            maxResults = PositiveInt(10),
                        ),
                    )
                }
                assertTrue(generationRead.await(10, TimeUnit.SECONDS))
                writer.upsertSymbolReference(
                    sourcePath = afterPath,
                    sourceOffset = 2,
                    targetFqName = "demo.Target",
                    targetPath = targetPath,
                    targetOffset = 1,
                )
                writerCommitted.countDown()
                readThread.join(10_000)
                assertFalse(readThread.isAlive, "snapshot reader did not complete")

                val page = requireNotNull(result.get())
                assertEquals(generationBeforeMutation, page.generation)
                assertEquals(
                    listOf(workspaceSourceRawPath(normalized, beforePath)),
                    page.page.references.map { it.sourcePath },
                )
            }
        }
    }

    @Test
    fun `manifest count remains available while an index read owns the store lock`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val firstPath = normalized.resolve("src/Manifest.kt").toString()
        val generationRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val count = AtomicReference<Int?>()
        SqliteSourceIndexStore(
            workspaceRoot = normalized,
            pageReadObserver = SourceIndexPageReadObserver {
                generationRead.countDown()
                assertTrue(releaseRead.await(10, TimeUnit.SECONDS))
            },
        ).use { store ->
            store.ensureSchema()
            store.saveManifest(mapOf(firstPath to 1L))
            val manifestFileCount = store.prepareManifestFileCountProvider()
            val readThread = thread(name = "source-index-lock-owner") {
                store.generatedReferencePageToSymbol(
                    targetFqName = "demo.Target",
                    offset = NonNegativeInt(0),
                    maxResults = PositiveInt(10),
                )
            }
            assertTrue(generationRead.await(10, TimeUnit.SECONDS))
            val countThread = thread(name = "source-index-manifest-count") {
                count.set(manifestFileCount())
            }
            try {
                countThread.join(1_000)
                assertFalse(countThread.isAlive, "manifest count waited for the store lock")
                assertEquals(1, count.get())
            } finally {
                releaseRead.countDown()
                countThread.join(10_000)
                readThread.join(10_000)
            }
            store.saveManifest(
                mapOf(
                    firstPath to 1L,
                    normalized.resolve("src/Second.kt").toString() to 2L,
                ),
            )
            assertEquals(2, manifestFileCount())
        }
    }

    @Test
    fun `generation advances for every committed reference content transition`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val callerPath = normalized.resolve("src/Caller.kt").toString()
        val targetPath = normalized.resolve("src/Target.kt").toString()
        val rebuiltPath = normalized.resolve("src/Rebuilt.kt").toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val initialGeneration = store.readGeneration()

            store.upsertSymbolReference(
                sourcePath = callerPath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = targetPath,
                targetOffset = 1,
            )
            assertEquals(initialGeneration.value + 1, store.readGeneration().value)

            store.clearReferencesFromFile(callerPath)
            assertEquals(initialGeneration.value + 2, store.readGeneration().value)

            store.saveFullIndex(
                updates = listOf(fileUpdate(rebuiltPath, "Rebuilt")),
                manifest = mapOf(rebuiltPath to 1L),
            )
            assertEquals(initialGeneration.value + 3, store.readGeneration().value)

            store.appendPendingUpdate(
                op = "upsert_ref",
                path = rebuiltPath,
                payload = """{"sourceOffset":2,"targetFqName":"demo.Target"}""",
            )
            assertEquals(1, store.reconcilePendingUpdates())
            assertEquals(initialGeneration.value + 4, store.readGeneration().value)
        }
    }

}
