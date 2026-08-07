package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

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
