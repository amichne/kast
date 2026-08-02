package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SourceIndexPageReadObserver
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.kastCacheDirectory
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
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

class SqliteSourceIndexInventoryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `source file inventory groups existing Kotlin files by source root`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val mainRoot = normalized.resolve("src/main/kotlin")
        val testRoot = normalized.resolve("src/test/kotlin")
        val mainFile = writeKotlinFile(mainRoot.resolve("demo/Main.kt"))
        val otherMainFile = writeKotlinFile(mainRoot.resolve("demo/Other.kt"))
        val testFile = writeKotlinFile(testRoot.resolve("demo/MainTest.kt"))
        val scriptPath = normalized.resolve("build.gradle.kts").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(mainFile.toString(), "Main"),
                    fileUpdate(otherMainFile.toString(), "Other"),
                    fileUpdate(testFile.toString(), "MainTest"),
                    fileUpdate(scriptPath, "GradleScript"),
                ),
                manifest = mapOf(
                    mainFile.toString() to 1L,
                    otherMainFile.toString() to 2L,
                    testFile.toString() to 3L,
                    scriptPath to 5L,
                ),
            )

            assertEquals(
                mapOf(
                    mainRoot to 2,
                    testRoot to 1,
                ),
                store.fileCountBySourceRoot(listOf(mainRoot, testRoot)),
            )
            assertEquals(
                mapOf(
                    mainRoot to listOf(mainFile.toRealPath(), otherMainFile.toRealPath()),
                    testRoot to listOf(testFile.toRealPath()),
                ),
                store.filesBySourceRoot(listOf(mainRoot, testRoot)),
            )
            assertEquals(
                mapOf(
                    mainRoot to listOf(mainFile.toRealPath()),
                    testRoot to listOf(testFile.toRealPath()),
                ),
                store.filesBySourceRoot(listOf(mainRoot, testRoot), limitPerRoot = 1),
            )
        }
    }

    @Test
    fun `source file counts are grouped by source root without requiring files to exist`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val mainRoot = normalized.resolve("src/main/kotlin")
        val indexedButMissingFile = mainRoot.resolve("demo/Missing.kt").toString()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(fileUpdate(indexedButMissingFile, "Missing")),
                manifest = mapOf(indexedButMissingFile to 1L),
            )

            assertEquals(
                mapOf(mainRoot to 1),
                store.fileCountBySourceRoot(listOf(mainRoot)),
            )
            assertEquals(
                mapOf(mainRoot to emptyList<Path>()),
                store.filesBySourceRoot(listOf(mainRoot)),
            )
        }
    }

    @Test
    fun `workspace root inventory excludes absolute paths outside the workspace`() {
        val normalized = workspaceRoot.resolve("workspace").toAbsolutePath().normalize()
        val insideFile = writeKotlinFile(normalized.resolve("src/Inside.kt"))
        val outsideFile = writeKotlinFile(workspaceRoot.resolve("outside/Outside.kt"))

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.saveFullIndex(
                updates = listOf(
                    fileUpdate(insideFile.toString(), "Inside"),
                    fileUpdate(outsideFile.toString(), "Outside"),
                ),
                manifest = mapOf(
                    insideFile.toString() to 1L,
                    outsideFile.toString() to 2L,
                ),
            )

            assertEquals(mapOf(normalized to 1), store.fileCountBySourceRoot(listOf(normalized)))
            assertEquals(mapOf(normalized to listOf(insideFile.toRealPath())), store.filesBySourceRoot(listOf(normalized)))
        }
    }

    @Test
    fun `module index progress records pending indexing and completion state`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val appA = workspaceSourceRawPath(normalized, writeKotlinFile(normalized.resolve("app/A.kt")).toString())
        val appB = workspaceSourceRawPath(normalized, writeKotlinFile(normalized.resolve("app/B.kt")).toString())
        val lib = workspaceSourceRawPath(normalized, writeKotlinFile(normalized.resolve("lib/Lib.kt")).toString())
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(
                    fileInventoryEntry(normalized, appA, 1, FileContentHash.parse("a".repeat(64)), ":app[main]", "main"),
                    fileInventoryEntry(normalized, appB, 1, FileContentHash.parse("b".repeat(64)), ":app[main]", "main"),
                    fileInventoryEntry(normalized, lib, 1, FileContentHash.parse("c".repeat(64)), ":lib[main]", "main"),
                ),
                FileStageVersions.CURRENT,
            )

            assertEquals(RelationshipIndexStatus.PENDING, store.moduleIndexStatus(":app[main]"))
            assertEquals(emptySet<String>(), store.completedModules())

            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                .associateBy { pending -> pending.path.rawPath }
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work.getValue(appA),
                        work.getValue(appA).contentHash,
                        emptyList(),
                        emptyList(),
                    ),
                ),
            )
            assertEquals(RelationshipIndexStatus.INDEXING, store.moduleIndexStatus(":app[main]"))

            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work.getValue(appB),
                        work.getValue(appB).contentHash,
                        emptyList(),
                        emptyList(),
                    ),
                ),
            )
            assertEquals(RelationshipIndexStatus.COMPLETE, store.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), store.completedModules())

            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work.getValue(lib),
                        work.getValue(lib).contentHash,
                        emptyList(),
                        emptyList(),
                    ),
                ),
            )
            assertEquals(setOf(":app[main]", ":lib[main]"), store.completedModules())
        }
    }

    @Test
    fun `module progress exposes no manual completion mutations`() {
        val methodNames = SqliteSourceIndexStore::class.java.methods.map { method -> method.name }

        assertFalse(methodNames.contains("initializeModuleProgress"))
        assertFalse(methodNames.contains("markModuleIndexing"))
        assertFalse(methodNames.contains("markModuleComplete"))
    }

    @Test
    fun `first empty inventory reconciliation commits completeness once`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            assertEquals(0L, store.readGeneration().value)

            store.reconcileFileInventory(emptyList(), FileStageVersions.CURRENT)
            assertEquals(1L, store.readGeneration().value)

            store.reconcileFileInventory(emptyList(), FileStageVersions.CURRENT)
            assertEquals(1L, store.readGeneration().value)
        }
    }

    @Test
    fun `adding inventory requeues limited relationship outcomes`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val caller = workspaceSourceRawPath(normalized, normalized.resolve("src/Caller.kt").toString())
        val target = workspaceSourceRawPath(normalized, normalized.resolve("src/Target.kt").toString())
        val callerEntry = fileInventoryEntry(
            normalized,
            caller,
            1,
            FileContentHash.parse("a".repeat(64)),
            ":app[main]",
            "main",
        )
        val targetEntry = fileInventoryEntry(
            normalized,
            target,
            1,
            FileContentHash.parse("b".repeat(64)),
            ":app[main]",
            "main",
        )

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(listOf(callerEntry), FileStageVersions.CURRENT)
            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work,
                        work.contentHash,
                        listOf(
                            SymbolReferenceRow(
                                sourcePath = caller,
                                sourceOffset = 1,
                                targetFqName = "demo.Missing",
                                targetPath = null,
                                targetOffset = null,
                            ),
                        ),
                        emptyList(),
                        limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP),
                    ),
                ),
            )
            assertTrue(store.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())
        }

        SqliteSourceIndexStore(normalized).use { store ->
            store.reconcileFileInventory(listOf(callerEntry, targetEntry), FileStageVersions.CURRENT)

            assertEquals(
                listOf(caller, target),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )
            assertNull(store.fileStageOutcome(caller, FileIndexStage.RELATIONSHIPS))
            assertEquals("demo.Missing", store.referencesFromFile(caller).single().targetFqName)
            assertEquals(RelationshipIndexStatus.PENDING, store.moduleIndexStatus(":app[main]"))
        }
    }

    @Test
    fun `symbol reference entry points reject Kotlin script paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val script = normalized.resolve("build.gradle.kts").toString()
        val caller = workspaceSourceRawPath(normalized, normalized.resolve("src/Caller.kt").toString())
        val target = workspaceSourceRawPath(normalized, normalized.resolve("src/Target.kt").toString())

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = script,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = target,
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = caller,
                sourceOffset = 2,
                targetFqName = "demo.Script",
                targetPath = script,
                targetOffset = 1,
            )

            assertTrue(store.referencesFromFile(script).isEmpty())
            val scriptReference = store.referencesToSymbol("demo.Script").single()
            assertEquals(caller, scriptReference.sourcePath)
            assertEquals(null, scriptReference.targetPath)
            assertEquals(null, scriptReference.targetOffset)
        }
    }

    @Test
    fun `inventory rejects a source proof minted for a different workspace root before mutation`() {
        val outerRoot = workspaceRoot.resolve("outer").also(Files::createDirectories)
        val storeRoot = outerRoot.resolve("nested").also(Files::createDirectories)
        val source = writeKotlinFile(storeRoot.resolve("src/App.kt"))
        val foreignEntry = fileInventoryEntry(
            workspaceRoot = outerRoot,
            path = source.toString(),
            lastModifiedMillis = 1,
            contentHash = FileContentHash.parse("a".repeat(64)),
            moduleName = ":app[main]",
            sourceSet = "main",
        )
        val localProof = workspaceSourcePath(storeRoot, source.toString())

        assertNotEquals(0, foreignEntry.path.compareTo(localProof))
        assertEquals(2, sortedSetOf(foreignEntry.path, localProof).size)

        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            val generationBefore = store.readGeneration()

            val failure = assertThrows(IllegalArgumentException::class.java) {
                store.reconcileFileInventory(listOf(foreignEntry), FileStageVersions.CURRENT)
            }

            assertTrue(failure.message.orEmpty().contains("different workspace root"))
            assertEquals(generationBefore, store.readGeneration())
            assertTrue(store.pendingFileStages(FileIndexStage.SOURCE).isEmpty())
        }
    }

}
