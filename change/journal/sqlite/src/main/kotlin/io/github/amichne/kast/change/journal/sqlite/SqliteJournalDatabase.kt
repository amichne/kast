package io.github.amichne.kast.change.journal.sqlite

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections

enum class AddDeclarationJournalDatabasePathFailure {
    NOT_CANONICAL_ABSOLUTE,
    PARENT_NOT_DIRECTORY,
    SYMLINK_NOT_ALLOWED,
    EXISTING_PATH_NOT_REGULAR_FILE,
}

@JvmInline
internal value class AddDeclarationJournalDatabase private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition:
         * `Path -> Refinement<AddDeclarationJournalDatabase,
         * AddDeclarationJournalDatabasePathFailure>`.
         *
         * Establishes a normalized absolute, non-symlink database path beneath an existing real
         * directory; any existing target is a regular file. The closed expected failure is
         * `AddDeclarationJournalDatabasePathFailure`; raw path extraction is permitted only at the
         * SQLite connection boundary.
         */
        fun admit(
            raw: Path,
        ): Refinement<AddDeclarationJournalDatabase, AddDeclarationJournalDatabasePathFailure> {
            if (!raw.isAbsolute || raw.normalize() != raw) {
                return Refinement.Rejected(
                    AddDeclarationJournalDatabasePathFailure.NOT_CANONICAL_ABSOLUTE,
                )
            }
            val parent = raw.parent
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return Refinement.Rejected(
                    AddDeclarationJournalDatabasePathFailure.PARENT_NOT_DIRECTORY,
                )
            }
            if (Files.isSymbolicLink(raw)) {
                return Refinement.Rejected(
                    AddDeclarationJournalDatabasePathFailure.SYMLINK_NOT_ALLOWED,
                )
            }
            if (Files.exists(raw, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)
            ) {
                return Refinement.Rejected(
                    AddDeclarationJournalDatabasePathFailure.EXISTING_PATH_NOT_REGULAR_FILE,
                )
            }
            return Refinement.Refined(AddDeclarationJournalDatabase(raw))
        }
    }
}

interface SqliteJournalConnectionObserver {
    fun opened()

    fun closed()

    fun committed(operation: SqliteJournalCommitOperation) = Unit

    fun rollingBack(operation: SqliteJournalCommitOperation) = Unit

    fun afterTransitionWrite(operation: SqliteJournalTransitionOperation) = Unit

    data object Disabled : SqliteJournalConnectionObserver {
        override fun opened() = Unit

        override fun closed() = Unit
    }
}

enum class SqliteJournalCommitOperation {
    APPLY_ADMISSION,
    APPLY_COMPLETION,
    VERIFICATION_COMPLETION,
}

enum class SqliteJournalTransitionOperation {
    PLAN_STORAGE,
    APPROVAL,
    RECOVERY_PREPARATION,
}

internal class SqliteJournalConnections(
    private val database: AddDeclarationJournalDatabase,
    private val observer: SqliteJournalConnectionObserver,
) {
    fun <T> use(block: (Connection) -> T): T {
        SqliteJournalDriver.ensureRegistered()
        val connection = DriverManager.getConnection("jdbc:sqlite:${database.path}")
        return try {
            observer.opened()
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 10000")
                statement.execute("PRAGMA foreign_keys = ON")
            }
            block(connection)
        } finally {
            connection.close()
            observer.closed()
        }
    }

    fun observeCommit(operation: SqliteJournalCommitOperation) {
        observer.committed(operation)
    }

    fun observeRollback(operation: SqliteJournalCommitOperation) {
        observer.rollingBack(operation)
    }

    fun observeTransitionWrite(operation: SqliteJournalTransitionOperation) {
        observer.afterTransitionWrite(operation)
    }
}

internal object SqliteJournalDriver {
    @Volatile
    private var bootstrapped = false

    fun ensureRegistered() {
        if (bootstrapped && hasDriver()) return
        synchronized(this) {
            if (bootstrapped && hasDriver()) return
            val driverClass = Class.forName(
                "org.sqlite.JDBC",
                true,
                SqliteJournalDriver::class.java.classLoader,
            )
            if (!hasDriver()) {
                DriverManager.registerDriver(driverClass.getDeclaredConstructor().newInstance() as Driver)
            }
            bootstrapped = true
        }
    }

    private fun hasDriver(): Boolean = Collections.list(DriverManager.getDrivers())
        .any { driver -> runCatching { driver.acceptsURL("jdbc:sqlite::memory:") }.getOrDefault(false) }
}

internal fun Connection.initializeAddDeclarationPlanJournal() {
    createStatement().use { statement ->
        statement.execute(
            """CREATE TABLE IF NOT EXISTS add_declaration_plan (
                plan_id TEXT PRIMARY KEY NOT NULL CHECK(length(plan_id) = 64),
                plan_bytes TEXT NOT NULL,
                source_generation INTEGER NOT NULL CHECK(source_generation >= 0),
                stage TEXT NOT NULL CHECK(stage IN ('AWAITING_APPROVAL', 'APPROVED')),
                state_version INTEGER NOT NULL CHECK(state_version >= 0),
                prior_stage TEXT,
                prior_version INTEGER,
                approval_plan_id TEXT,
                approval_by TEXT,
                approval_sha256 TEXT,
                CHECK(
                    (stage = 'AWAITING_APPROVAL' AND state_version = 0 AND
                        prior_stage IS NULL AND prior_version IS NULL AND
                        approval_plan_id IS NULL AND approval_by IS NULL AND approval_sha256 IS NULL)
                    OR
                    (stage = 'APPROVED' AND state_version = 1 AND
                        prior_stage = 'AWAITING_APPROVAL' AND prior_version = 0 AND
                        approval_plan_id = plan_id AND approval_by IS NOT NULL AND
                        approval_sha256 IS NOT NULL AND length(approval_sha256) = 64)
                )
            ) WITHOUT ROWID""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS add_declaration_recovery (
                plan_id TEXT PRIMARY KEY NOT NULL REFERENCES add_declaration_plan(plan_id),
                state_version INTEGER NOT NULL CHECK(state_version = 2),
                prior_stage TEXT NOT NULL CHECK(prior_stage = 'APPROVED'),
                prior_version INTEGER NOT NULL CHECK(prior_version = 1),
                target_path TEXT NOT NULL,
                before_sha256 TEXT NOT NULL CHECK(length(before_sha256) = 64),
                before_content_base64 TEXT NOT NULL,
                mutation_progress TEXT NOT NULL CHECK(mutation_progress = 'NOT_BEGUN')
            ) WITHOUT ROWID""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS add_declaration_apply (
                plan_id TEXT PRIMARY KEY NOT NULL REFERENCES add_declaration_recovery(plan_id),
                stage TEXT NOT NULL CHECK(stage IN ('APPLY_ADMITTED', 'APPLIED_UNVERIFIED')),
                state_version INTEGER NOT NULL CHECK(state_version IN (3, 4)),
                prior_stage TEXT NOT NULL,
                prior_version INTEGER NOT NULL,
                observed_target_path TEXT,
                after_sha256 TEXT,
                after_content_base64 TEXT,
                CHECK(
                    (stage = 'APPLY_ADMITTED' AND state_version = 3 AND
                        prior_stage = 'RECOVERY_PREPARED' AND prior_version = 2 AND
                        observed_target_path IS NULL AND after_sha256 IS NULL AND
                        after_content_base64 IS NULL)
                    OR
                    (stage = 'APPLIED_UNVERIFIED' AND state_version = 4 AND
                        prior_stage = 'APPLY_ADMITTED' AND prior_version = 3 AND
                        observed_target_path IS NOT NULL AND length(after_sha256) = 64 AND
                        after_content_base64 IS NOT NULL)
                )
            ) WITHOUT ROWID""",
        )
        statement.execute(
            """CREATE TABLE IF NOT EXISTS add_declaration_verification (
                plan_id TEXT PRIMARY KEY NOT NULL REFERENCES add_declaration_apply(plan_id),
                stage TEXT NOT NULL CHECK(stage = 'VERIFIED'),
                state_version INTEGER NOT NULL CHECK(state_version = 5),
                prior_stage TEXT NOT NULL CHECK(prior_stage = 'APPLIED_UNVERIFIED'),
                prior_version INTEGER NOT NULL CHECK(prior_version = 4),
                publication_generation INTEGER NOT NULL CHECK(publication_generation >= 0),
                publication_identity TEXT NOT NULL CHECK(length(publication_identity) > 0),
                verified_target_path TEXT NOT NULL,
                observed_start_offset INTEGER NOT NULL CHECK(observed_start_offset >= 0),
                observed_end_offset INTEGER NOT NULL CHECK(observed_end_offset > observed_start_offset),
                observed_package_name TEXT NOT NULL,
                observed_declaration_name TEXT NOT NULL,
                observed_declaration_kind TEXT NOT NULL,
                verified_postimage_sha256 TEXT NOT NULL CHECK(length(verified_postimage_sha256) = 64)
            ) WITHOUT ROWID""",
        )
    }
}
