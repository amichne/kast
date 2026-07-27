package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
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
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 42,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Other.kt",
                sourceOffset = 7,
                targetFqName = "lib.Foo",
                targetPath = "/src/Foo.kt",
                targetOffset = 10,
            )

            assertEquals(2, store.referencesToSymbol("lib.Foo").size)
            store.clearReferencesFromFile("/src/Caller.kt")

            assertTrue(store.referencesFromFile("/src/Caller.kt").isEmpty())
            assertEquals(1, store.referencesToSymbol("lib.Foo").size)
        }
    }

    @Test
    fun `symbol reference pages bound high cardinality lookup work and continue deterministically`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            repeat(500) { index ->
                store.upsertSymbolReference(
                    sourcePath = "/src/Caller${index.toString().padStart(3, '0')}.kt",
                    sourceOffset = index,
                    targetFqName = "lib.HighCardinality",
                    targetPath = "/src/HighCardinality.kt",
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
                (0 until 8).map { index -> "/src/Caller${index.toString().padStart(3, '0')}.kt" },
                first.page.references.map { it.sourcePath } + second.page.references.map { it.sourcePath },
            )
        }
    }

    @Test
    fun `exact reference pages isolate declarations that share an fq name`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/FirstCaller.kt",
                sourceOffset = 1,
                targetFqName = "lib.overloaded",
                targetPath = "/src/Target.kt",
                targetOffset = 10,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/SecondCaller.kt",
                sourceOffset = 2,
                targetFqName = "lib.overloaded",
                targetPath = "/src/Target.kt",
                targetOffset = 40,
            )

            val page = store.generatedReferencePageToExactSymbol(
                target = ExactReferenceTarget(
                    fqName = "lib.overloaded",
                    declarationFile = NormalizedPath.parse("/src/Target.kt"),
                    declarationStartOffset = NonNegativeInt(40),
                ),
                offset = NonNegativeInt(0),
                maxResults = PositiveInt(10),
            )

            assertEquals(listOf("/src/SecondCaller.kt"), page.page.references.map { it.sourcePath })
            assertTrue(page.page.references.all { it.targetOffset == 40 })
        }
    }

    @Test
    fun `exact reference pages reject rows without declaration identity`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/src/LegacyCaller.kt",
                sourceOffset = 1,
                targetFqName = "lib.Target",
                targetPath = null,
                targetOffset = null,
            )

            val page = store.generatedReferencePageToExactSymbol(
                target = ExactReferenceTarget(
                    fqName = "lib.Target",
                    declarationFile = NormalizedPath.parse("/src/Target.kt"),
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
        val generationRead = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val result = AtomicReference<io.github.amichne.kast.indexstore.api.reference.GeneratedSymbolReferencePage>()
        SqliteSourceIndexStore(normalized).use { writer ->
            writer.ensureSchema()
            writer.upsertSymbolReference(
                sourcePath = "/src/Before.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            val generationBeforeMutation = writer.readGeneration()
            SqliteSourceIndexStore(
                workspaceRoot = normalized,
                pageReadObserver = SourceIndexPageReadObserver {
                    generationRead.countDown()
                    assertTrue(writerCommitted.await(10, TimeUnit.SECONDS))
                },
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
                    sourcePath = "/src/After.kt",
                    sourceOffset = 2,
                    targetFqName = "demo.Target",
                    targetPath = "/src/Target.kt",
                    targetOffset = 1,
                )
                writerCommitted.countDown()
                readThread.join(10_000)
                assertFalse(readThread.isAlive, "snapshot reader did not complete")

                val page = requireNotNull(result.get())
                assertEquals(generationBeforeMutation, page.generation)
                assertEquals(listOf("/src/Before.kt"), page.page.references.map { it.sourcePath })
            }
        }
    }

    @Test
    fun `generation advances for every committed reference content transition`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            val initialGeneration = store.readGeneration()

            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            assertEquals(initialGeneration.value + 1, store.readGeneration().value)

            store.clearReferencesFromFile("/src/Caller.kt")
            assertEquals(initialGeneration.value + 2, store.readGeneration().value)

            store.saveFullIndex(
                updates = listOf(fileUpdate("/src/Rebuilt.kt", "Rebuilt")),
                manifest = mapOf("/src/Rebuilt.kt" to 1L),
            )
            assertEquals(initialGeneration.value + 3, store.readGeneration().value)

            store.appendPendingUpdate(
                op = "upsert_ref",
                path = "/src/Rebuilt.kt",
                payload = """{"sourceOffset":2,"targetFqName":"demo.Target"}""",
            )
            assertEquals(1, store.reconcilePendingUpdates())
            assertEquals(initialGeneration.value + 4, store.readGeneration().value)
        }
    }

}
