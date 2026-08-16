package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections

enum class SqliteMutationRecoveryJournalOpenFailure {
    NOT_CANONICAL_ABSOLUTE,
    PARENT_NOT_DIRECTORY,
    SYMLINK_NOT_ALLOWED,
    EXISTING_PATH_NOT_REGULAR_FILE,
    STORAGE_UNAVAILABLE,
}

@JvmInline
internal value class SqliteMutationRecoveryDatabase private constructor(
    val path: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> Refinement<SqliteMutationRecoveryDatabase,
         * SqliteMutationRecoveryJournalOpenFailure>`.
         *
         * Establishes a normalized absolute, non-symlink database target beneath an existing real
         * directory, with any existing target a regular file. The closed expected failure is
         * [SqliteMutationRecoveryJournalOpenFailure]. Raw path extraction is permitted only at the
         * JDBC connection boundary.
         */
        fun admit(
            raw: Path,
        ): Refinement<SqliteMutationRecoveryDatabase, SqliteMutationRecoveryJournalOpenFailure> =
            when {
                !raw.isAbsolute || raw.normalize() != raw -> Refinement.Rejected(
                    SqliteMutationRecoveryJournalOpenFailure.NOT_CANONICAL_ABSOLUTE,
                )
                raw.parent == null || !Files.isDirectory(raw.parent, LinkOption.NOFOLLOW_LINKS) ->
                    Refinement.Rejected(
                        SqliteMutationRecoveryJournalOpenFailure.PARENT_NOT_DIRECTORY,
                    )
                Files.isSymbolicLink(raw) -> Refinement.Rejected(
                    SqliteMutationRecoveryJournalOpenFailure.SYMLINK_NOT_ALLOWED,
                )
                Files.exists(raw, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS) -> Refinement.Rejected(
                    SqliteMutationRecoveryJournalOpenFailure.EXISTING_PATH_NOT_REGULAR_FILE,
                )
                else -> Refinement.Refined(SqliteMutationRecoveryDatabase(raw))
            }
    }
}

internal enum class MutationRecoveryFaultPoint {
    AFTER_PREPARE_WRITE,
    AFTER_PREPARE_COMMIT,
    AFTER_APPLIED_WRITE,
    AFTER_APPLIED_COMMIT,
    AFTER_TERMINAL_WRITE,
    AFTER_TERMINAL_COMMIT,
}

internal fun interface MutationRecoveryFaultInjector {
    fun observe(point: MutationRecoveryFaultPoint)

    data object Disabled : MutationRecoveryFaultInjector {
        override fun observe(point: MutationRecoveryFaultPoint) = Unit
    }
}

internal class SqliteMutationRecoveryConnections(
    private val database: SqliteMutationRecoveryDatabase,
) {
    fun <T> use(block: (Connection) -> T): T {
        ensureSqliteDriver()
        return DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 10000")
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA synchronous = FULL")
            }
            block(connection)
        }
    }

    fun initialize() = use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS mutation_recovery (
                    plan_binding TEXT PRIMARY KEY NOT NULL CHECK(length(plan_binding) = 64),
                    stage TEXT NOT NULL CHECK(stage IN (
                        'PRE_WRITE_DURABLE', 'APPLIED_WRITES_DURABLE',
                        'ROLLED_BACK', 'RECOVERY_REQUIRED'
                    )),
                    state_version INTEGER NOT NULL CHECK(state_version BETWEEN 0 AND 2),
                    recovery_requirement TEXT,
                    record_digest TEXT NOT NULL CHECK(length(record_digest) = 64),
                    CHECK(
                        (stage = 'PRE_WRITE_DURABLE' AND state_version = 0 AND
                            recovery_requirement IS NULL) OR
                        (stage = 'APPLIED_WRITES_DURABLE' AND state_version = 1 AND
                            recovery_requirement IS NULL) OR
                        (stage = 'ROLLED_BACK' AND state_version = 2 AND
                            recovery_requirement IS NULL) OR
                        (stage = 'RECOVERY_REQUIRED' AND state_version = 2 AND
                            recovery_requirement = 'ROLLBACK_REJECTED')
                    )
                ) WITHOUT ROWID""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS mutation_recovery_planned_write (
                    plan_binding TEXT NOT NULL REFERENCES mutation_recovery(plan_binding),
                    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                    source_path TEXT NOT NULL,
                    preimage_sha256 TEXT NOT NULL CHECK(length(preimage_sha256) = 64),
                    preimage_base64 TEXT NOT NULL,
                    PRIMARY KEY(plan_binding, ordinal),
                    UNIQUE(plan_binding, source_path)
                ) WITHOUT ROWID""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS mutation_recovery_applied_write (
                    plan_binding TEXT NOT NULL REFERENCES mutation_recovery(plan_binding),
                    ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                    source_path TEXT NOT NULL,
                    PRIMARY KEY(plan_binding, ordinal),
                    UNIQUE(plan_binding, source_path)
                ) WITHOUT ROWID""",
            )
        }
    }
}

private fun ensureSqliteDriver() {
    if (Collections.list(DriverManager.getDrivers()).any(::acceptsSqlite)) return
    val driverClass = Class.forName(
        "org.sqlite.JDBC",
        true,
        SqliteMutationRecoveryConnections::class.java.classLoader,
    )
    if (!Collections.list(DriverManager.getDrivers()).any(::acceptsSqlite)) {
        DriverManager.registerDriver(driverClass.getDeclaredConstructor().newInstance() as Driver)
    }
}

private fun acceptsSqlite(driver: Driver): Boolean =
    runCatching { driver.acceptsURL("jdbc:sqlite::memory:") }.getOrDefault(false)
