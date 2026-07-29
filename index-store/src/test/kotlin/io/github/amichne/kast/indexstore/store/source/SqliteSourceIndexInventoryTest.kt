package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
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
                    mainRoot to listOf(mainFile, otherMainFile),
                    testRoot to listOf(testFile),
                ),
                store.filesBySourceRoot(listOf(mainRoot, testRoot)),
            )
            assertEquals(
                mapOf(
                    mainRoot to listOf(mainFile),
                    testRoot to listOf(testFile),
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
            assertEquals(mapOf(normalized to listOf(insideFile)), store.filesBySourceRoot(listOf(normalized)))
        }
    }

    @Test
    fun `module index progress records pending indexing and completion state`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val appA = writeKotlinFile(normalized.resolve("app/A.kt")).toString()
        val appB = writeKotlinFile(normalized.resolve("app/B.kt")).toString()
        val lib = writeKotlinFile(normalized.resolve("lib/Lib.kt")).toString()
        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(
                    FileInventoryEntry(appA, 1, FileContentHash.parse("a".repeat(64)), ":app[main]", "main"),
                    FileInventoryEntry(appB, 1, FileContentHash.parse("b".repeat(64)), ":app[main]", "main"),
                    FileInventoryEntry(lib, 1, FileContentHash.parse("c".repeat(64)), ":lib[main]", "main"),
                ),
                FileStageVersions.CURRENT,
            )

            assertEquals("PENDING", store.moduleIndexStatus(":app[main]"))
            assertEquals(emptySet<String>(), store.completedModules())

            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).associateBy { pending -> pending.path }
            store.commitRelationshipBatch(
                listOf(RelationshipFileStageUpdate(work.getValue(appA), emptyList(), emptyList())),
            )
            assertEquals("INDEXING", store.moduleIndexStatus(":app[main]"))

            store.commitRelationshipBatch(
                listOf(RelationshipFileStageUpdate(work.getValue(appB), emptyList(), emptyList())),
            )
            assertEquals("COMPLETE", store.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), store.completedModules())

            store.commitRelationshipBatch(
                listOf(RelationshipFileStageUpdate(work.getValue(lib), emptyList(), emptyList())),
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
    fun `adding inventory requeues limited relationship outcomes`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val caller = normalized.resolve("src/Caller.kt").toString()
        val target = normalized.resolve("src/Target.kt").toString()
        val callerEntry = FileInventoryEntry(
            caller,
            1,
            FileContentHash.parse("a".repeat(64)),
            ":app[main]",
            "main",
        )
        val targetEntry = FileInventoryEntry(
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
                        emptyList(),
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
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path },
            )
            assertNull(store.fileStageOutcome(caller, FileIndexStage.RELATIONSHIPS))
        }
    }

    @Test
    fun `symbol reference entry points reject Kotlin script paths`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = "/build.gradle.kts",
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = "/src/Target.kt",
                targetOffset = 1,
            )
            store.upsertSymbolReference(
                sourcePath = "/src/Caller.kt",
                sourceOffset = 2,
                targetFqName = "demo.Script",
                targetPath = "/build.gradle.kts",
                targetOffset = 1,
            )

            assertTrue(store.referencesFromFile("/build.gradle.kts").isEmpty())
            val scriptReference = store.referencesToSymbol("demo.Script").single()
            assertEquals("/src/Caller.kt", scriptReference.sourcePath)
            assertEquals(null, scriptReference.targetPath)
            assertEquals(null, scriptReference.targetOffset)
        }
    }

    @Test
    fun `ensureSchema does not run compatibility cleanup for current schema`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val dbPath = sourceIndexDatabasePath(normalized)

        SqliteSourceIndexStore(normalized).use { store -> store.ensureSchema() }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT OR IGNORE INTO path_prefixes (prefix_id, dir_path) VALUES (100, '')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (100, 'demo.GradleScript')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (101, 'demo.CaseSensitive')")
                stmt.execute("INSERT OR IGNORE INTO fq_names (fq_id, fq_name) VALUES (102, 'demo.ScriptTarget')")
                stmt.execute("INSERT INTO identifier_paths (identifier, prefix_id, filename) VALUES ('GradleScript', 100, 'build.gradle.kts')")
                stmt.execute("INSERT INTO identifier_paths (identifier, prefix_id, filename) VALUES ('CaseSensitive', 100, 'Foo.KT')")
                stmt.execute("INSERT INTO file_metadata (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (100, 'build.gradle.kts', 100, 'PROVEN_NAMED', NULL, ':main', 'main')")
                stmt.execute("INSERT INTO file_metadata (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (100, 'Foo.KT', 101, 'PROVEN_NAMED', NULL, ':main', 'main')")
                stmt.execute("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (100, 'build.gradle.kts', 1)")
                stmt.execute("INSERT INTO file_manifest (prefix_id, filename, last_modified_millis) VALUES (100, 'Foo.KT', 1)")
                stmt.execute("INSERT INTO file_imports (prefix_id, filename, fq_id) VALUES (100, 'build.gradle.kts', 100)")
                stmt.execute("INSERT INTO file_wildcard_imports (prefix_id, filename, fq_id) VALUES (100, 'build.gradle.kts', 100)")
                stmt.execute(
                    """INSERT INTO symbol_references
                       (src_prefix_id, src_filename, source_offset, target_fq_id, tgt_prefix_id, tgt_filename, target_offset)
                       VALUES (100, 'build.gradle.kts', 1, 100, 100, 'build.gradle.kts', 1)""",
                )
                stmt.execute(
                    """INSERT INTO symbol_references
                       (src_prefix_id, src_filename, source_offset, target_fq_id, tgt_prefix_id, tgt_filename, target_offset)
                       VALUES (100, 'Caller.kt', 2, 102, 100, 'build.gradle.kts', 1)""",
                )
            }
        }

        SqliteSourceIndexStore(normalized).use { store ->
            assertTrue(store.ensureSchema())
        }

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            assertEquals(1, tableCount(conn, "identifier_paths", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "identifier_paths", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_metadata", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_metadata", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_manifest", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_manifest", "filename = 'Foo.KT'"))
            assertEquals(1, tableCount(conn, "file_imports", "filename = 'build.gradle.kts'"))
            assertEquals(1, tableCount(conn, "file_wildcard_imports", "filename = 'build.gradle.kts'"))
            assertEquals(2, tableCount(conn, "symbol_references", "src_filename = 'build.gradle.kts' OR tgt_filename = 'build.gradle.kts'"))
            conn.prepareStatement("SELECT tgt_filename, target_offset FROM symbol_references WHERE src_filename = 'Caller.kt'").use { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("build.gradle.kts", rs.getString("tgt_filename"))
                assertEquals(1, rs.getInt("target_offset"))
            }
        }
    }

}
