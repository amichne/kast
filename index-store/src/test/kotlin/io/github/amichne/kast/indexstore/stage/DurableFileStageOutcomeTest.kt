package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.index.RelationshipIndexStatus
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.cache.sourceIndexDatabasePath
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException

class DurableFileStageOutcomeTest : DurableFileStageTestFixture() {
    @Test
    fun `file stage limitations use their typed kotlinx serializer`() {
        val limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP)

        val encoded = Json.encodeToString(limitations)

        assertEquals("[\"UNRESOLVED_RELATIONSHIP\"]", encoded)
        assertEquals(limitations, Json.decodeFromString<List<FileStageLimitation>>(encoded))
    }

    @Test
    fun `failed relationship batch rolls back facts limitations progress and generation`() {
        val path = file("src/App.kt")
        val entries = listOf(inventory(path, hash('a'), ":app[main]"))

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions("1"))
            commitSources(store, FileIndexStage.SOURCE, listOf(path))
            val firstWork = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work = firstWork, scannedContentHash = firstWork.contentHash,
                        references = listOf(reference(path, "demo.Preserved")),
                        declarations = emptyList(),
                    ),
                ),
            )
            store.reconcileFileInventory(entries, versions("1").copy(relationships = version("2")))
            val generation = store.readGeneration()
            val status = store.moduleIndexStatus(":app[main]")
            val outcome = store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS)
            val references = store.referencesFromFile(path)

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
                            work = work, scannedContentHash = work.contentHash,
                            references = listOf(reference(path, "demo.Replacement")),
                            declarations = emptyList(),
                            limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP),
                        ),
                    ),
                )
            }

            assertEquals(references, store.referencesFromFile(path))
            assertEquals(outcome, store.fileStageOutcome(path, FileIndexStage.RELATIONSHIPS))
            assertEquals(FileStageOutcomeStatus.COMPLETE, outcome?.status)
            assertEquals(status, store.moduleIndexStatus(":app[main]"))
            assertEquals(generation, store.readGeneration())
        }
    }

    @Test
    fun `limited file keeps valid facts and completes a usable degraded scope`() {
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
                        work = work, scannedContentHash = work.contentHash,
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
            assertEquals(RelationshipIndexStatus.DEGRADED, store.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), store.completedModules())
        }

        SqliteSourceIndexStore(workspaceRoot).use { reopened ->
            assertEquals(1, reopened.referencesToSymbol("demo.Valid").size)
            assertTrue(reopened.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())
            assertTrue(
                reopened.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, path) is
                    FileStageScopeCoverage.Limited,
            )
            assertEquals(RelationshipIndexStatus.DEGRADED, reopened.moduleIndexStatus(":app[main]"))
            assertEquals(setOf(":app[main]"), reopened.completedModules())
        }
    }
}
