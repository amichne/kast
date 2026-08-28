package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.TopologyDatabaseLocation
import io.github.amichne.kast.topology.contract.CompleteTopologyGeneration
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologyPublicationFailure
import io.github.amichne.kast.topology.contract.TopologyPublicationResult
import io.github.amichne.kast.topology.contract.TopologySnapshotContentRead
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotReadFailure
import io.github.amichne.kast.topology.contract.TopologySnapshotStore
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.CancellationException

enum class SqliteTopologySnapshotStoreFailure {
    NOT_CANONICAL_ABSOLUTE,
    PARENT_NOT_DIRECTORY,
    SYMLINK_NOT_ALLOWED,
    EXISTING_PATH_NOT_REGULAR_FILE,
    STORAGE_UNAVAILABLE,
}

sealed interface SqliteTopologySnapshotStoreOpening {
    data class Opened(
        val store: SqliteTopologySnapshotStore,
    ) : SqliteTopologySnapshotStoreOpening

    data class Rejected(
        val failure: SqliteTopologySnapshotStoreFailure,
    ) : SqliteTopologySnapshotStoreOpening
}

internal enum class SqliteTopologyFaultPoint {
    BEFORE_COMMIT,
}

internal fun interface SqliteTopologyFaultInjector {
    fun observe(point: SqliteTopologyFaultPoint)

    data object Disabled : SqliteTopologyFaultInjector {
        override fun observe(point: SqliteTopologyFaultPoint) = Unit
    }
}

/** Direct SQLite reader and sole production publisher for durable topology snapshots. */
class SqliteTopologySnapshotStore private constructor(
    private val path: Path,
    private val faultInjector: SqliteTopologyFaultInjector,
) : TopologySnapshotStore {
    override fun eligible(identity: TopologyWorkspaceIdentity): TopologySnapshotEligibility = try {
        connect().use { connection ->
            when (val exact = connection.findExactTopologySnapshot(identity)) {
                is SqliteTopologySnapshotLookup.Found -> when (
                    connection.readTopologyContent(exact.record.snapshot)
                ) {
                    is TopologySnapshotContentRead.Loaded ->
                        TopologySnapshotEligibility.Eligible(exact.record.snapshot)
                    is TopologySnapshotContentRead.Rejected ->
                        TopologySnapshotEligibility.Rejected(
                            TopologySnapshotReadFailure.CORRUPT_SNAPSHOT,
                        )
                }
                SqliteTopologySnapshotLookup.Absent -> when (
                    val latest = connection.findLatestTopologySnapshot(identity.lease.workspaceRoot)
                ) {
                    is SqliteTopologySnapshotLookup.Found -> when (
                        connection.readTopologyContent(latest.record.snapshot)
                    ) {
                        is TopologySnapshotContentRead.Loaded ->
                            TopologySnapshotEligibility.Stale(latest.record.snapshot)
                        is TopologySnapshotContentRead.Rejected ->
                            TopologySnapshotEligibility.Rejected(
                                TopologySnapshotReadFailure.CORRUPT_SNAPSHOT,
                            )
                    }
                    SqliteTopologySnapshotLookup.Absent -> TopologySnapshotEligibility.Unavailable
                }
            }
        }
    } catch (failure: Exception) {
        failure.rethrowCancellation()
        TopologySnapshotEligibility.Rejected(
            if (failure is SqliteTopologyCorruption) {
                TopologySnapshotReadFailure.CORRUPT_SNAPSHOT
            } else {
                TopologySnapshotReadFailure.STORAGE_UNAVAILABLE
            },
        )
    }

    override fun read(snapshot: PublishedTopologySnapshot): TopologySnapshotContentRead = try {
        connect().use { connection -> connection.readTopologyContent(snapshot) }
    } catch (failure: Exception) {
        failure.rethrowCancellation()
        TopologySnapshotContentRead.Rejected(
            if (failure is SqliteTopologyCorruption) {
                TopologySnapshotReadFailure.CORRUPT_SNAPSHOT
            } else {
                TopologySnapshotReadFailure.STORAGE_UNAVAILABLE
            },
        )
    }

    override fun publish(
        generation: CompleteTopologyGeneration,
    ): TopologyPublicationResult {
        val connection = try {
            connect().also { it.autoCommit = false }
        } catch (failure: Exception) {
            failure.rethrowCancellation()
            return rejectedPublication(TopologyPublicationFailure.STORAGE_UNAVAILABLE)
        }
        return try {
            when (val existing = connection.findExactTopologySnapshot(generation.identity)) {
                is SqliteTopologySnapshotLookup.Found -> {
                    connection.rollback()
                    return when (connection.readTopologyContent(existing.record.snapshot)) {
                        is TopologySnapshotContentRead.Loaded ->
                            if (existing.record.snapshot.manifest == generation.manifest()) {
                                TopologyPublicationResult.Unchanged(existing.record.snapshot)
                            } else {
                                rejectedPublication(TopologyPublicationFailure.SNAPSHOT_CONFLICT)
                            }
                        is TopologySnapshotContentRead.Rejected ->
                            rejectedPublication(TopologyPublicationFailure.CORRUPT_SNAPSHOT)
                    }
                }
                SqliteTopologySnapshotLookup.Absent -> Unit
            }
            val inserted = connection.insertTopologySnapshot(generation)
            connection.insertTopologyContent(inserted.snapshotId, generation)
            when (connection.readTopologyContent(inserted.snapshot)) {
                is TopologySnapshotContentRead.Loaded -> Unit
                is TopologySnapshotContentRead.Rejected -> {
                    connection.rollback()
                    return rejectedPublication(TopologyPublicationFailure.CORRUPT_SNAPSHOT)
                }
            }
            faultInjector.observe(SqliteTopologyFaultPoint.BEFORE_COMMIT)
            connection.commit()
            TopologyPublicationResult.Published(inserted.snapshot)
        } catch (failure: Exception) {
            runCatching { connection.rollback() }
            failure.rethrowCancellation()
            rejectedPublication(
                if (failure is SqliteTopologyCorruption) {
                    TopologyPublicationFailure.CORRUPT_SNAPSHOT
                } else {
                    TopologyPublicationFailure.STORAGE_UNAVAILABLE
                },
            )
        } finally {
            runCatching { connection.close() }
        }
    }

    private fun connect(): Connection {
        ensureTopologySqliteDriver()
        return DriverManager.getConnection("jdbc:sqlite:$path").also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 10000")
                statement.execute("PRAGMA synchronous = FULL")
                statement.execute("PRAGMA foreign_keys = ON")
            }
        }
    }

    companion object {
        /** Opens one exact-root durable location without exposing a raw path to composition. */
        fun open(location: TopologyDatabaseLocation): SqliteTopologySnapshotStoreOpening {
            val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
                ?: return SqliteTopologySnapshotStoreOpening.Rejected(
                    SqliteTopologySnapshotStoreFailure.STORAGE_UNAVAILABLE,
                )
            return open(path)
        }

        /**
         * Proof transition: `Path -> SqliteTopologySnapshotStoreOpening`.
         *
         * Establishes a normalized absolute, non-symlink database target below an existing
         * directory, an existing regular file when present, and a ready topology schema.
         * [SqliteTopologySnapshotStoreFailure] is the closed expected failure. Raw path extraction
         * is permitted only at the JDBC connection boundary in this module.
         */
        fun open(raw: Path): SqliteTopologySnapshotStoreOpening =
            open(raw, SqliteTopologyFaultInjector.Disabled)

        internal fun open(
            raw: Path,
            faultInjector: SqliteTopologyFaultInjector,
        ): SqliteTopologySnapshotStoreOpening {
            val rejected = when {
                !raw.isAbsolute || raw.normalize() != raw ->
                    SqliteTopologySnapshotStoreFailure.NOT_CANONICAL_ABSOLUTE
                raw.parent == null || !Files.isDirectory(raw.parent, LinkOption.NOFOLLOW_LINKS) ->
                    SqliteTopologySnapshotStoreFailure.PARENT_NOT_DIRECTORY
                Files.isSymbolicLink(raw) ->
                    SqliteTopologySnapshotStoreFailure.SYMLINK_NOT_ALLOWED
                Files.exists(raw, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS) ->
                    SqliteTopologySnapshotStoreFailure.EXISTING_PATH_NOT_REGULAR_FILE
                else -> null
            }
            if (rejected != null) return SqliteTopologySnapshotStoreOpening.Rejected(rejected)
            return try {
                val store = SqliteTopologySnapshotStore(raw, faultInjector)
                store.connect().use(::initializeTopologySchema)
                SqliteTopologySnapshotStoreOpening.Opened(store)
            } catch (failure: Exception) {
                failure.rethrowCancellation()
                SqliteTopologySnapshotStoreOpening.Rejected(
                    SqliteTopologySnapshotStoreFailure.STORAGE_UNAVAILABLE,
                )
            }
        }
    }
}

private fun CompleteTopologyGeneration.manifest() =
    io.github.amichne.kast.topology.contract.TopologySnapshotManifest.from(this)

private fun rejectedPublication(failure: TopologyPublicationFailure) =
    TopologyPublicationResult.Rejected(failure)

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private fun ensureTopologySqliteDriver() {
    if (Collections.list(DriverManager.getDrivers()).any(::acceptsTopologySqlite)) return
    val driverClass = Class.forName(
        "org.sqlite.JDBC",
        true,
        SqliteTopologySnapshotStore::class.java.classLoader,
    )
    if (!Collections.list(DriverManager.getDrivers()).any(::acceptsTopologySqlite)) {
        DriverManager.registerDriver(driverClass.getDeclaredConstructor().newInstance() as Driver)
    }
}

private fun acceptsTopologySqlite(driver: Driver): Boolean =
    runCatching { driver.acceptsURL("jdbc:sqlite::memory:") }.getOrDefault(false)

internal class SqliteTopologyCorruption(message: String) : IllegalStateException(message)
