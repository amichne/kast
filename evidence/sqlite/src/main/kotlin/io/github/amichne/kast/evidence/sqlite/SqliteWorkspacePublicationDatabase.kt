package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.EvidenceGenerationFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.CancellationException

enum class SqliteWorkspacePublicationDatabaseFailure {
    NOT_CANONICAL_ABSOLUTE,
    PARENT_NOT_DIRECTORY,
    SYMLINK_NOT_ALLOWED,
    EXISTING_PATH_NOT_REGULAR_FILE,
    STORAGE_UNAVAILABLE,
}

sealed interface SqliteWorkspacePublicationDatabaseOpening {
    data class Opened(
        val database: SqliteWorkspacePublicationDatabase,
    ) : SqliteWorkspacePublicationDatabaseOpening

    data class Rejected(
        val failure: SqliteWorkspacePublicationDatabaseFailure,
    ) : SqliteWorkspacePublicationDatabaseOpening
}

/** Strongly admitted direct SQLite authority for canonical workspace publication evidence. */
class SqliteWorkspacePublicationDatabase private constructor(
    internal val path: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> SqliteWorkspacePublicationDatabaseOpening`.
         *
         * Establishes a normalized absolute, non-symlink database target beneath an existing
         * directory, with any existing target a regular file and the publication schema ready.
         * The closed expected failure is [SqliteWorkspacePublicationDatabaseFailure]. Raw path
         * extraction is permitted only at the JDBC connection boundary in this module.
         */
        fun open(raw: Path): SqliteWorkspacePublicationDatabaseOpening {
            val rejected = when {
                !raw.isAbsolute || raw.normalize() != raw ->
                    SqliteWorkspacePublicationDatabaseFailure.NOT_CANONICAL_ABSOLUTE
                raw.parent == null ||
                !Files.isDirectory(raw.parent, LinkOption.NOFOLLOW_LINKS) ->
                    SqliteWorkspacePublicationDatabaseFailure.PARENT_NOT_DIRECTORY
                Files.isSymbolicLink(raw) ->
                    SqliteWorkspacePublicationDatabaseFailure.SYMLINK_NOT_ALLOWED
                Files.exists(raw, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS) ->
                    SqliteWorkspacePublicationDatabaseFailure.EXISTING_PATH_NOT_REGULAR_FILE
                else -> null
            }
            if (rejected != null) {
                return SqliteWorkspacePublicationDatabaseOpening.Rejected(rejected)
            }
            return try {
                val database = SqliteWorkspacePublicationDatabase(raw)
                database.initialize()
                SqliteWorkspacePublicationDatabaseOpening.Opened(database)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                SqliteWorkspacePublicationDatabaseOpening.Rejected(
                    SqliteWorkspacePublicationDatabaseFailure.STORAGE_UNAVAILABLE,
                )
            }
        }
    }

    internal fun current(): SqliteWorkspacePublicationRecord? = connect().use { connection ->
        connection.prepareStatement(
            "SELECT generation, identity, graph_publication " +
            "FROM workspace_publication WHERE singleton = 1",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                SqliteWorkspacePublicationRecord(
                    publication = PublishedWorkspaceGeneration(
                        generation = evidenceGeneration(rows.getLong("generation")),
                        identity = WorkspaceStateIdentity(rows.getString("identity")),
                    ),
                    graphPublication = WorkspaceGraphPublication.valueOf(
                        rows.getString("graph_publication"),
                    ),
                )
            }
        }
    }

    internal fun begin(
        faultInjector: SqliteWorkspacePublicationFaultInjector,
    ): SqliteWorkspacePublicationSession {
        val connection = connect()
        return try {
            connection.autoCommit = false
            val priorGeneration = connection.prepareStatement(
                "SELECT generation FROM workspace_publication WHERE singleton = 1",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) else 0L
                }
            }
            SqliteWorkspacePublicationSession(
                connection = connection,
                priorGeneration = priorGeneration,
                nextGeneration = evidenceGeneration(Math.addExact(priorGeneration, 1L)),
                faultInjector = faultInjector,
            )
        } catch (failure: Exception) {
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun initialize() = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS workspace_publication (
                    singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton = 1),
                    generation INTEGER NOT NULL CHECK(generation > 0),
                    identity TEXT NOT NULL CHECK(length(identity) > 0),
                    graph_publication TEXT NOT NULL CHECK(
                        graph_publication IN ('Ready', 'IndexingBlocked')
                    )
                )""",
            )
        }
    }

    private fun connect(): Connection {
        ensurePublicationSqliteDriver()
        return DriverManager.getConnection("jdbc:sqlite:$path").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 10000")
                statement.execute("PRAGMA synchronous = FULL")
            }
        }
    }
}

internal data class SqliteWorkspacePublicationRecord(
    val publication: PublishedWorkspaceGeneration,
    val graphPublication: WorkspaceGraphPublication,
)

internal enum class SqliteWorkspacePublicationFaultPoint {
    BEFORE_COMMIT,
}

internal fun interface SqliteWorkspacePublicationFaultInjector {
    fun observe(point: SqliteWorkspacePublicationFaultPoint)

    data object Disabled : SqliteWorkspacePublicationFaultInjector {
        override fun observe(point: SqliteWorkspacePublicationFaultPoint) = Unit
    }
}

internal class SqliteWorkspacePublicationSession(
    private val connection: Connection,
    private val priorGeneration: Long,
    private val nextGeneration: EvidenceGeneration,
    private val faultInjector: SqliteWorkspacePublicationFaultInjector,
) {
    private var state: State = State.Open

    fun prepare(
        identity: WorkspaceStateIdentity,
        graphPublication: WorkspaceGraphPublication,
    ) {
        check(state == State.Open) { "SQLite workspace publication is not open" }
        state = State.Prepared(identity, graphPublication)
    }

    fun commit(): SqliteWorkspacePublicationRecord {
        val prepared = state as? State.Prepared
                       ?: error("SQLite workspace publication is not prepared")
        val record = SqliteWorkspacePublicationRecord(
            PublishedWorkspaceGeneration(nextGeneration, prepared.identity),
            prepared.graphPublication,
        )
        try {
            val changed = connection.prepareStatement(
                """INSERT INTO workspace_publication(
                       singleton, generation, identity, graph_publication
                   ) VALUES (1, ?, ?, ?)
                   ON CONFLICT(singleton) DO UPDATE SET
                       generation = excluded.generation,
                       identity = excluded.identity,
                       graph_publication = excluded.graph_publication
                   WHERE workspace_publication.generation = ?""",
            ).use { statement ->
                statement.setLong(1, record.publication.generation.value)
                statement.setString(2, record.publication.identity.value)
                statement.setString(3, record.graphPublication.name)
                statement.setLong(4, priorGeneration)
                statement.executeUpdate()
            }
            check(changed == 1) { "Workspace publication generation moved before commit" }
            faultInjector.observe(SqliteWorkspacePublicationFaultPoint.BEFORE_COMMIT)
            connection.commit()
            state = State.Committed
            connection.close()
            return record
        } catch (failure: Exception) {
            runCatching { connection.rollback() }
            throw failure
        }
    }

    fun discard() {
        check(state is State.Open || state is State.Prepared) {
            "SQLite workspace publication cannot be discarded from $state"
        }
        connection.rollback()
        connection.close()
        state = State.Discarded
    }

    private sealed interface State {
        data object Open : State

        data class Prepared(
            val identity: WorkspaceStateIdentity,
            val graphPublication: WorkspaceGraphPublication,
        ) : State

        data object Committed : State

        data object Discarded : State
    }
}

/**
 * Proof transition: `Long -> EvidenceGeneration`.
 *
 * Re-establishes the non-negative generation invariant at the SQLite extraction boundary. The
 * database schema and monotonic session calculation exclude the closed [EvidenceGenerationFailure];
 * a violated persisted invariant is storage corruption and cannot be weakened into a publication.
 * Raw generation extraction is permitted only at this SQLite result-set boundary.
 */
private fun evidenceGeneration(raw: Long): EvidenceGeneration = when (
    val parsed = EvidenceGeneration.parse(raw)
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("SQLite workspace publication generation is negative: $raw")
}

private fun ensurePublicationSqliteDriver() {
    if (Collections.list(DriverManager.getDrivers()).any(::acceptsPublicationSqlite)) return
    val driverClass = Class.forName(
        "org.sqlite.JDBC",
        true,
        SqliteWorkspacePublicationDatabase::class.java.classLoader,
    )
    if (!Collections.list(DriverManager.getDrivers()).any(::acceptsPublicationSqlite)) {
        DriverManager.registerDriver(driverClass.getDeclaredConstructor().newInstance() as Driver)
    }
}

private fun acceptsPublicationSqlite(driver: Driver): Boolean =
    runCatching { driver.acceptsURL("jdbc:sqlite::memory:") }.getOrDefault(false)
