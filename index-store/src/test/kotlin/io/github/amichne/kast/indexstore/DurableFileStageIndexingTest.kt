package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

class DurableFileStageIndexingTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `restart reuses unchanged outcomes and invalidates only affected file stages`() {
        val paths = listOf(file("src/A.kt"), file("src/B.kt"))
        val entries = paths.mapIndexed { index, path -> inventory(path, hash('a' + index), ":app[main]") }
        val versions = versions("1")

        val committedGeneration = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions)
            commitSources(store, FileIndexStage.SOURCE, paths)
            commitRelationships(store, paths)
            store.readGeneration()
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.reconcileFileInventory(entries, versions)
            assertEquals(committedGeneration, store.readGeneration())
            assertTrue(store.pendingFileStages(FileIndexStage.SOURCE).isEmpty())
            assertTrue(store.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())

            val changed = entries.map { entry ->
                if (entry.path == paths.first()) entry.copy(contentHash = hash('c')) else entry
            }
            store.reconcileFileInventory(changed, versions)
            assertEquals(
                listOf(paths.first()),
                store.pendingFileStages(FileIndexStage.SOURCE).map { work -> work.path },
            )
            assertEquals(
                listOf(paths.first()),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path },
            )

            commitSources(store, FileIndexStage.SOURCE, listOf(paths.first()))
            store.reconcileFileInventory(changed, versions.copy(relationships = version("2")))
            assertTrue(store.pendingFileStages(FileIndexStage.SOURCE).isEmpty())
            assertEquals(
                paths,
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path },
            )
        }
    }

    @Test
    fun `failed relationship batch leaves facts outcomes progress and generation unchanged`() {
        val path = file("src/App.kt")
        val entries = listOf(inventory(path, hash('a'), ":app[main]"))

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions("1"))
            commitSources(store, FileIndexStage.SOURCE, listOf(path))
            val generation = store.readGeneration()
            val status = store.moduleIndexStatus(":app[main]")

            DriverManager.getConnection("jdbc:sqlite:${sourceIndexDatabasePath(workspaceRoot)}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """CREATE TRIGGER reject_relationship_outcome
                           BEFORE INSERT ON file_stage_outcomes
                           WHEN NEW.stage = 'RELATIONSHIPS'
                           BEGIN
                               SELECT RAISE(FAIL, 'injected relationship outcome failure');
                           END""",
                    )
                }
            }

            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()
            assertThrows(SQLException::class.java) {
                store.commitRelationshipBatch(
                    listOf(
                        RelationshipFileStageUpdate(
                            work = work,
                            references = listOf(reference(path, "demo.Target")),
                            declarations = emptyList(),
                        ),
                    ),
                )
            }

            assertTrue(store.referencesToSymbol("demo.Target").isEmpty())
            assertNull(store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS))
            assertEquals(status, store.moduleIndexStatus(":app[main]"))
            assertEquals(generation, store.readGeneration())
        }
    }

    @Test
    fun `limited file keeps valid facts and cannot complete its persisted scope`() {
        val path = file("src/App.kt")
        val entries = listOf(inventory(path, hash('a'), ":app[main]"))

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions("1"))
            commitSources(store, FileIndexStage.SOURCE, listOf(path))
            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work = work,
                        references = listOf(reference(path, "demo.Valid")),
                        declarations = emptyList(),
                        limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP),
                    ),
                ),
            )

            assertEquals(1, store.referencesToSymbol("demo.Valid").size)
            val outcome = store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)
            assertEquals(FileStageOutcomeStatus.LIMITED, outcome?.status)
            assertEquals(listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP), outcome?.limitations)
            assertTrue(
                store.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, path) is
                    FileStageScopeCoverage.Limited,
            )
            assertTrue(store.completedModules().isEmpty())
        }

        SqliteSourceIndexStore(workspaceRoot).use { reopened ->
            assertEquals(1, reopened.referencesToSymbol("demo.Valid").size)
            assertTrue(reopened.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())
            assertTrue(
                reopened.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, path) is
                    FileStageScopeCoverage.Limited,
            )
            assertTrue(reopened.completedModules().isEmpty())
        }
    }

    @Test
    fun `independent sessions complete only the scopes backed by persisted outcomes`() {
        val app = file("app/src/main/kotlin/App.kt")
        val lib = file("lib/src/main/kotlin/Lib.kt")
        val entries = listOf(
            inventory(app, hash('a'), ":app[main]"),
            inventory(lib, hash('b'), ":lib[main]"),
        )

        SqliteSourceIndexStore(workspaceRoot).use { firstSession ->
            firstSession.ensureSchema()
            firstSession.reconcileFileInventory(entries, versions("1"))
            commitSources(firstSession, FileIndexStage.SOURCE, listOf(app, lib))
            commitRelationships(firstSession, listOf(app))
            assertTrue(
                firstSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, app) is
                    FileStageScopeCoverage.Complete,
            )
            assertTrue(
                firstSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, lib) is
                    FileStageScopeCoverage.Limited,
            )
            assertEquals(setOf(":app[main]"), firstSession.completedModules())
        }

        SqliteSourceIndexStore(workspaceRoot).use { secondSession ->
            assertEquals(
                listOf(lib),
                secondSession.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path },
            )
            commitRelationships(secondSession, listOf(lib))
            assertTrue(
                secondSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, app) is
                    FileStageScopeCoverage.Complete,
            )
            assertTrue(
                secondSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, lib) is
                    FileStageScopeCoverage.Complete,
            )
            assertEquals(setOf(":app[main]", ":lib[main]"), secondSession.completedModules())
        }
    }

    @Test
    fun `interrupted multi session batches converge with uninterrupted batches`() {
        val resumedRoot = workspaceRoot.resolve("resumed")
        val uninterruptedRoot = workspaceRoot.resolve("uninterrupted")
        val relativePaths = listOf("src/A.kt", "src/B.kt", "src/C.kt")

        val resumedFacts = indexInBatches(resumedRoot, relativePaths, reopenAfterFirstBatch = true)
        val uninterruptedFacts = indexInBatches(uninterruptedRoot, relativePaths, reopenAfterFirstBatch = false)

        assertEquals(uninterruptedFacts, resumedFacts)
    }

    private fun indexInBatches(
        root: Path,
        relativePaths: List<String>,
        reopenAfterFirstBatch: Boolean,
    ): PersistedFacts {
        val paths = relativePaths.map { relative -> root.resolve(relative).toAbsolutePath().normalize().toString() }
        val entries = paths.mapIndexed { index, path -> inventory(path, hash('a' + index), ":app[main]") }
        SqliteSourceIndexStore(root).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions("1"))
            commitSources(store, FileIndexStage.SOURCE, paths)
            commitRelationships(store, paths.take(1))
            if (!reopenAfterFirstBatch) commitRelationships(store, paths.drop(1))
        }
        SqliteSourceIndexStore(root).use { store ->
            if (reopenAfterFirstBatch) commitRelationships(store, paths.drop(1))
            return PersistedFacts(
                references = paths.associateWith { path -> store.referencesFromFile(path) },
                completedModules = store.completedModules(),
                generation = store.readGeneration().value,
            )
        }
    }

    private fun commitSources(
        store: SqliteSourceIndexStore,
        stage: FileIndexStage,
        paths: Collection<String>,
    ) {
        val workByPath = store.pendingFileStages(stage).associateBy { work -> work.path }
        store.commitSourceBatch(
            paths.map { path ->
                SourceFileStageUpdate(
                    work = workByPath.getValue(path),
                    update = sourceUpdate(path),
                )
            },
        )
    }

    private fun commitRelationships(store: SqliteSourceIndexStore, paths: Collection<String>) {
        val workByPath = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
            .associateBy { work -> work.path }
        store.commitRelationshipBatch(
            paths.map { path ->
                RelationshipFileStageUpdate(
                    work = workByPath.getValue(path),
                    references = listOf(reference(path, "demo.${Path.of(path).fileName}")),
                    declarations = emptyList(),
                )
            },
        )
    }

    private fun inventory(path: String, hash: FileContentHash, moduleName: String): FileInventoryEntry =
        FileInventoryEntry(
            path = path,
            lastModifiedMillis = 1,
            contentHash = hash,
            moduleName = moduleName,
            sourceSet = "main",
        )

    private fun sourceUpdate(path: String): FileIndexUpdate =
        FileIndexUpdate(
            path = path,
            identifiers = setOf(Path.of(path).fileName.toString().removeSuffix(".kt")),
            packageName = "demo",
            modulePath = ":app",
            sourceSet = "main",
            imports = emptySet(),
            wildcardImports = emptySet(),
            packageEvidence = IndexedPackageEvidence.ProvenNamed(
                IndexedPackageEvidence.CanonicalName.parse("demo"),
            ),
        )

    private fun reference(path: String, target: String): SymbolReferenceRow =
        SymbolReferenceRow(
            sourcePath = path,
            sourceOffset = 1,
            targetFqName = target,
            targetPath = null,
            targetOffset = null,
        )

    private fun file(relative: String): String {
        val path = workspaceRoot.resolve(relative).toAbsolutePath().normalize()
        Files.createDirectories(path.parent)
        Files.writeString(path, "package demo")
        return path.toString()
    }

    private fun versions(value: String): FileStageVersions =
        FileStageVersions(
            source = version(value),
            relationships = version(value),
            semanticGraph = version(value),
        )

    private fun version(value: String): FileStageVersion = FileStageVersion.parse("test-$value")

    private fun hash(character: Char): FileContentHash = FileContentHash.parse(character.toString().repeat(64))

    private data class PersistedFacts(
        val references: Map<String, List<SymbolReferenceRow>>,
        val completedModules: Set<String>,
        val generation: Long,
    )
}
