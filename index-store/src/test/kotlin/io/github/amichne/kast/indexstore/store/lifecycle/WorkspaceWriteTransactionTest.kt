package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

class WorkspaceWriteTransactionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `workspace write stays invisible until its single database commit`() {
        val workspaceRoot = tempDir.resolve("workspace")
        val database = tempDir.resolve("workspace-data/cache/source-index.db")
        val baseIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot)
        val identity = baseIdentity.copy(
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )

        SqliteSourceIndexStore(identity).use { store ->
            store.ensureSchema()
            val write = store.beginWorkspaceWrite()
            store.writeHeadCommit("staged")

            assertEquals(null, readHeadCommit(database))

            store.discardWorkspaceWrite(write)
            assertEquals(null, readHeadCommit(database))
        }
    }

    @Test
    fun `temporary SQLite state is file backed under large reconciliation pressure`() {
        val workspaceRoot = tempDir.resolve("resource-workspace").toAbsolutePath().normalize()
        val state = SqliteSourceIndexStoreState(
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
            pageReadObserver = SourceIndexPageReadObserver.Disabled,
        )

        state.use {
            val tempStore = state.connection().createStatement().use { statement ->
                statement.executeQuery("PRAGMA temp_store").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }

            assertEquals(1, tempStore, "SQLite temp tables and sorts must spill to files, not native memory")
        }
    }

    @Test
    fun `simulated write exhaustion rolls back and leaves the store reusable`() {
        val workspaceRoot = tempDir.resolve("write-pressure-workspace").toAbsolutePath().normalize()
        val state = SqliteSourceIndexStoreState(
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot),
            pageReadObserver = SourceIndexPageReadObserver.Disabled,
        )

        state.use {
            val connection = state.connection()
            val generationBefore = state.readGenerationInTransaction(connection)
            val currentPages = pragmaInt(connection, "page_count")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA max_page_count=${currentPages + 1}")
            }

            org.junit.jupiter.api.Assertions.assertThrows(SQLException::class.java) {
                state.writeTransaction { transaction ->
                    transaction.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO workspace_discovery(cache_key, schema_version, payload) " +
                                "VALUES ('write-pressure', 1, zeroblob(1048576))",
                        )
                    }
                    state.incrementGenerationInTransaction(transaction)
                }
            }

            assertEquals(generationBefore, state.readGenerationInTransaction(connection))
            assertEquals(0, tableRowCount(connection, "workspace_discovery"))

            connection.createStatement().use { statement ->
                statement.execute("PRAGMA max_page_count=1073741823")
            }
            state.writeTransaction { transaction ->
                state.incrementGenerationInTransaction(transaction)
            }
            assertEquals(generationBefore.value + 1, state.readGenerationInTransaction(connection).value)
        }
    }

    private fun pragmaInt(connection: java.sql.Connection, name: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun tableRowCount(connection: java.sql.Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private fun readHeadCommit(database: Path): String? =
        DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=ro").use { connection ->
            connection.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }
}
