package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.AddDeclarationPlanPersistenceFailure
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournal
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenFailure
import io.github.amichne.kast.change.journal.sqlite.SqliteAddDeclarationPlanJournalOpenResult
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistence
import io.github.amichne.kast.change.plan.service.AddDeclarationPlanPersistenceService
import java.nio.file.Path

internal const val ADD_DECLARATION_PLAN_JOURNAL_FILE_NAME = "add-declaration-plans.db"

internal sealed interface AddDeclarationPlanPersistenceBootstrap {
    class Ready private constructor(
        val journal: SqliteAddDeclarationPlanJournal,
        val persistence: AddDeclarationPlanPersistence,
    ) : AddDeclarationPlanPersistenceBootstrap {
        companion object {
            /**
             * Proof transition: `SqliteAddDeclarationPlanJournal -> Ready`.
             *
             * Retains the full durable lifecycle authority and derives the narrower planning
             * persistence capability from that exact journal. There is no expected failure; raw
             * storage access remains confined to the SQLite adapter.
             */
            fun fromJournal(journal: SqliteAddDeclarationPlanJournal): Ready = Ready(
                journal = journal,
                persistence = AddDeclarationPlanPersistenceService(journal),
            )
        }
    }

    data class Rejected(
        val failure: AddDeclarationPlanPersistenceFailure,
    ) : AddDeclarationPlanPersistenceBootstrap
}

/**
 * Proof transition: `Path -> AddDeclarationPlanPersistenceBootstrap`.
 *
 * A ready result establishes an initialized workspace-scoped SQLite journal whose bootstrap
 * connection is closed and retains both its full durable lifecycle authority and the derived
 * detached planning capability. The closed expected failure is
 * `AddDeclarationPlanPersistenceFailure`; the raw database path is extracted only by the SQLite
 * journal adapter.
 */
internal fun openAddDeclarationPlanPersistence(
    databasePath: Path,
): AddDeclarationPlanPersistenceBootstrap =
    when (val opened = SqliteAddDeclarationPlanJournal.open(databasePath)) {
        is SqliteAddDeclarationPlanJournalOpenResult.Opened ->
            AddDeclarationPlanPersistenceBootstrap.Ready.fromJournal(opened.journal)
        is SqliteAddDeclarationPlanJournalOpenResult.Rejected ->
            AddDeclarationPlanPersistenceBootstrap.Rejected(
                when (opened.failure) {
                    is SqliteAddDeclarationPlanJournalOpenFailure.InvalidDatabasePath ->
                        AddDeclarationPlanPersistenceFailure.DATABASE_PATH_INVALID
                    SqliteAddDeclarationPlanJournalOpenFailure.StorageUnavailable ->
                        AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE
                },
            )
    }
