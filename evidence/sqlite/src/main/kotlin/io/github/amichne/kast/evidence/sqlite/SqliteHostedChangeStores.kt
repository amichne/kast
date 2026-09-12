package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.Refinement

sealed interface SqliteHostedChangeStoresFailure {
    data class Database(val cause: SqliteMutationRecoveryJournalOpenFailure) : SqliteHostedChangeStoresFailure

    data class Plans(val cause: LiveChangePlanStoreFailure) : SqliteHostedChangeStoresFailure

    data class Receipts(val cause: LiveChangeReceiptStoreFailure) : SqliteHostedChangeStoresFailure
}

/** All durable change stores retain one admitted and initialized connection source. */
class SqliteHostedChangeStores
private constructor(
    val plans: SqliteLiveChangePlanStore,
    val journal: SqliteMutationRecoveryJournal,
    val receipts: SqliteLiveChangeReceiptStore,
) {
    companion object {
        fun open(
            location: MutationDatabaseLocation
        ): Refinement<SqliteHostedChangeStores, SqliteHostedChangeStoresFailure> {
            val path =
                when (val result = admitHostedDatabasePath(location.valueAtSqliteBoundary())) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(SqliteHostedChangeStoresFailure.Database(result.failure))
                }
            val database =
                when (val result = SqliteMutationRecoveryDatabase.admit(path)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(SqliteHostedChangeStoresFailure.Database(result.failure))
                }
            val connections =
                try {
                    SqliteMutationRecoveryConnections(database).initialize()
                } catch (_: java.sql.SQLException) {
                    return Refinement.Rejected(
                        SqliteHostedChangeStoresFailure.Database(
                            SqliteMutationRecoveryJournalOpenFailure.STORAGE_UNAVAILABLE
                        )
                    )
                }
            val plans =
                when (val result = SqliteLiveChangePlanStore.retain(connections)) {
                    is SqliteLiveChangePlanStoreOpenResult.Opened -> result.store
                    is SqliteLiveChangePlanStoreOpenResult.Rejected ->
                        return Refinement.Rejected(SqliteHostedChangeStoresFailure.Plans(result.failure))
                }
            val receipts =
                when (val result = SqliteLiveChangeReceiptStore.retain(connections)) {
                    is SqliteLiveChangeReceiptStoreOpenResult.Opened -> result.store
                    is SqliteLiveChangeReceiptStoreOpenResult.Rejected ->
                        return Refinement.Rejected(SqliteHostedChangeStoresFailure.Receipts(result.failure))
                }
            return Refinement.Refined(
                SqliteHostedChangeStores(plans, SqliteMutationRecoveryJournal.retain(connections), receipts)
            )
        }
    }
}
