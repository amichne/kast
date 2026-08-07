package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.jdbc.SqliteJdbcDriverBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class WorkspaceGenerationStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `publication commits staged workspace facts and revision atomically`() {
        val database = tempDir.resolve("workspace-data/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val generations = WorkspaceGenerationStore(
                sourceStore,
                publicationClock = { PublicationEpochMillis.fromClock(42) },
            )
            val open = generations.begin()
            sourceStore.writeHeadCommit("staged-head")
            val prepared = generations.prepare(open, PublishedWorkspaceIdentity("verified-state"))

            assertNull(readPublication(database))
            assertNull(readHeadCommit(database))

            val committed = generations.commit(prepared).manifest

            assertEquals(WorkspaceSemanticGeneration(1), committed.generation)
            assertEquals(PublicationEpochMillis.fromClock(42), committed.publishedAt)
            assertEquals("verified-state", readPublication(database)?.identity?.value)
            assertEquals("staged-head", readHeadCommit(database))
            assertFalse(Files.exists(database.parent.resolve("semantic-generations")))
        }
    }

    @Test
    fun `discard rolls back staged facts and publication`() {
        val database = tempDir.resolve("workspace-data/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val generations = WorkspaceGenerationStore(sourceStore)
            val open = generations.begin()
            sourceStore.writeHeadCommit("discarded-head")
            val prepared = generations.prepare(open, PublishedWorkspaceIdentity("discarded-state"))

            generations.discard(prepared)

            assertNull(readPublication(database))
            assertNull(readHeadCommit(database))
        }
    }

    @Test
    fun `each committed transaction advances the logical revision in the same database`() {
        val database = tempDir.resolve("workspace-data/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val generations = WorkspaceGenerationStore(sourceStore)

            val first = generations.prepare(generations.begin(), PublishedWorkspaceIdentity("first"))
            assertEquals(WorkspaceSemanticGeneration(1), generations.commit(first).manifest.generation)

            val second = generations.prepare(generations.begin(), PublishedWorkspaceIdentity("second"))
            assertEquals(WorkspaceSemanticGeneration(2), generations.commit(second).manifest.generation)

            assertEquals("second", readPublication(database)?.identity?.value)
            assertEquals(database, database.parent.resolve("source-index.db"))
        }
    }

    @Test
    fun `incomplete workspace cannot prepare a publication`() {
        val database = tempDir.resolve("workspace-data/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedIncompleteModule(database)
            val generations = WorkspaceGenerationStore(sourceStore)
            val open = generations.begin()

            val failure = assertThrows(WorkspacePublicationRejectedException::class.java) {
                generations.prepare(open, PublishedWorkspaceIdentity("incomplete"))
            }
            generations.discard(open)

            assertEquals(
                WorkspacePublicationReadinessFailure.ModulesIncomplete(
                    io.github.amichne.kast.api.contract.NonNegativeInt(1),
                ),
                failure.failure,
            )
            assertNull(readPublication(database))
        }
    }

    private fun workspaceIdentity(database: Path): WorkspaceIdentity {
        val workspaceRoot = tempDir.resolve("workspace")
        Files.createDirectories(workspaceRoot)
        val base = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)
        return base.copy(
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )
    }

    private fun seedCompleteModule(database: Path) = seedModule(database, "COMPLETE", 1, 1)

    private fun seedIncompleteModule(database: Path) = seedModule(database, "INDEXING", 0, 1)

    private fun seedModule(
        database: Path,
        status: String,
        indexedFiles: Int,
        totalFiles: Int,
    ) {
        SqliteJdbcDriverBootstrap.ensureRegistered()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.prepareStatement(
                """INSERT INTO module_index_progress(
                       module_name, relationship_index_status, indexed_file_count,
                       total_file_count, last_indexed_epoch_ms
                   ) VALUES ('app', ?, ?, ?, 1)""",
            ).use { statement ->
                statement.setString(1, status)
                statement.setInt(2, indexedFiles)
                statement.setInt(3, totalFiles)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun readHeadCommit(database: Path): String? = readOnly(database) { connection ->
        connection.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    private fun readPublication(database: Path): PublishedWorkspaceGenerationManifest? = readOnly(database) { connection ->
        connection.prepareStatement(
            """SELECT revision, identity, source_index_generation, source_index_schema_version,
                      published_at_epoch_millis, repository_overlay_file
               FROM workspace_publication WHERE singleton = 1""",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                PublishedWorkspaceGenerationManifest(
                    generation = WorkspaceSemanticGeneration(rows.getLong("revision")),
                    identity = PublishedWorkspaceIdentity(rows.getString("identity")),
                    sourceIndexGeneration = io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration(
                        rows.getLong("source_index_generation"),
                    ),
                    sourceIndexSchemaVersion = SourceIndexSchemaVersion(
                        rows.getInt("source_index_schema_version"),
                    ),
                    publishedAt = PublicationEpochMillis.fromClock(rows.getLong("published_at_epoch_millis")),
                    repositoryOverlay = when (
                        val resolution = RepositoryOverlayPublication.fromSerializedFileName(
                            rows.getString("repository_overlay_file"),
                        )
                    ) {
                        is RepositoryOverlayPublicationResolution.Resolved -> resolution.publication
                        is RepositoryOverlayPublicationResolution.Rejected -> error(resolution.failure)
                    },
                )
            }
        }
    }

    private fun <T> readOnly(database: Path, read: (java.sql.Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=ro").use(read)
}
