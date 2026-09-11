package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.verify.HistoricalLiveAddDeclarationReceipt
import io.github.amichne.kast.change.verify.LiveAddDeclarationReceiptCodec
import io.github.amichne.kast.change.verify.LiveChangeReceiptIssuance
import io.github.amichne.kast.change.verify.LiveChangeReceiptLookup
import io.github.amichne.kast.change.verify.LiveChangeReceiptStore
import io.github.amichne.kast.change.verify.LiveChangeReceiptStoreFailure
import io.github.amichne.kast.change.verify.LiveReceiptFailure
import io.github.amichne.kast.change.verify.VerifiedLiveAddDeclarationReceipt
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException

sealed interface SqliteLiveChangeReceiptStoreOpenResult {
    data class Opened(val store: SqliteLiveChangeReceiptStore) : SqliteLiveChangeReceiptStoreOpenResult

    data class Rejected(val failure: LiveChangeReceiptStoreFailure) : SqliteLiveChangeReceiptStoreOpenResult
}

/** Immutable receipts coexist with live plans and all legacy/recovery rows in the installed mutation database. */
class SqliteLiveChangeReceiptStore
private constructor(private val connections: InitializedSqliteMutationRecoveryConnections) : LiveChangeReceiptStore {
    override fun issueReceipt(receipt: VerifiedLiveAddDeclarationReceipt): LiveChangeReceiptIssuance =
        persistHistorical(receipt.historical)

    /**
     * Adapter-local persistence of an already admitted historical projection; public issuance requires complete proof.
     */
    internal fun persistHistorical(receipt: HistoricalLiveAddDeclarationReceipt): LiveChangeReceiptIssuance {
        val planIdentity = planIdentity(receipt)
        val document = LiveAddDeclarationReceiptCodec.encode(receipt)
        val digest = digest(document)
        return storage({ LiveChangeReceiptIssuance.Rejected(it) }) {
            connections.use { connection ->
                connection
                    .prepareStatement(
                        """INSERT OR IGNORE INTO live_change_receipt(
                    plan_identity, receipt_identity, codec_version, document, document_sha256
                ) VALUES (?, ?, ?, ?, ?)"""
                    )
                    .use { statement ->
                        statement.setString(1, planIdentity.value)
                        statement.setString(2, receipt.identity.value)
                        statement.setInt(RECEIPT_VERSION_PARAMETER, LiveAddDeclarationReceiptCodec.VERSION)
                        statement.setString(RECEIPT_DOCUMENT_PARAMETER, document)
                        statement.setString(RECEIPT_DIGEST_PARAMETER, digest)
                        statement.executeUpdate()
                    }
                val expected =
                    LiveReceiptRow(
                        receiptIdentity = receipt.identity.value,
                        version = LiveAddDeclarationReceiptCodec.VERSION.toLong(),
                        document = document,
                        digest = digest,
                    )
                connection.confirmReceipt(planIdentity, expected)
            }
        }
    }

    private fun Connection.confirmReceipt(
        identity: ChangePlanIdentity,
        expected: LiveReceiptRow,
    ): LiveChangeReceiptIssuance {
        val row =
            when (val observed = receiptRow(identity)) {
                LiveReceiptRowObservation.Missing ->
                    return LiveChangeReceiptIssuance.Rejected(LiveChangeReceiptStoreFailure.IDENTITY_COLLISION)
                is LiveReceiptRowObservation.Found -> observed.row
            }
        if (row != expected) return LiveChangeReceiptIssuance.Rejected(LiveChangeReceiptStoreFailure.IDENTITY_COLLISION)
        return when (val loaded = decode(identity, row)) {
            is LiveChangeReceiptLookup.Found -> LiveChangeReceiptIssuance.Issued(loaded.identity, loaded.receipt)
            LiveChangeReceiptLookup.Missing ->
                LiveChangeReceiptIssuance.Rejected(LiveChangeReceiptStoreFailure.STORAGE_UNAVAILABLE)
            is LiveChangeReceiptLookup.Rejected -> LiveChangeReceiptIssuance.Rejected(loaded.failure)
        }
    }

    override fun loadReceipt(planIdentity: ChangePlanIdentity): LiveChangeReceiptLookup =
        storage({ LiveChangeReceiptLookup.Rejected(it) }) {
            connections.use { connection ->
                when (val observed = connection.receiptRow(planIdentity)) {
                    LiveReceiptRowObservation.Missing -> LiveChangeReceiptLookup.Missing
                    is LiveReceiptRowObservation.Found -> decode(planIdentity, observed.row)
                }
            }
        }

    private fun decode(planIdentity: ChangePlanIdentity, row: LiveReceiptRow): LiveChangeReceiptLookup {
        if (row.version != LiveAddDeclarationReceiptCodec.VERSION.toLong())
            return LiveChangeReceiptLookup.Rejected(LiveChangeReceiptStoreFailure.VERSION_UNSUPPORTED)
        if (digest(row.document) != row.digest)
            return LiveChangeReceiptLookup.Rejected(LiveChangeReceiptStoreFailure.CORRUPT_RECORD)
        val receipt =
            when (val decoded = LiveAddDeclarationReceiptCodec.decode(row.document)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected ->
                    return LiveChangeReceiptLookup.Rejected(
                        when (decoded.failure) {
                            LiveReceiptFailure.VERSION_UNSUPPORTED -> LiveChangeReceiptStoreFailure.VERSION_UNSUPPORTED
                            else -> LiveChangeReceiptStoreFailure.CORRUPT_RECORD
                        }
                    )
            }
        if (receipt.identity.value != row.receiptIdentity || planIdentity(receipt) != planIdentity) {
            return LiveChangeReceiptLookup.Rejected(LiveChangeReceiptStoreFailure.CORRUPT_RECORD)
        }
        return LiveChangeReceiptLookup.Found(receipt.identity, receipt)
    }

    companion object {
        fun open(location: MutationDatabaseLocation): SqliteLiveChangeReceiptStoreOpenResult {
            val path =
                prepareHostedDatabasePath(location.valueAtSqliteBoundary())
                    ?: return SqliteLiveChangeReceiptStoreOpenResult.Rejected(
                        LiveChangeReceiptStoreFailure.STORAGE_UNAVAILABLE
                    )
            val database =
                when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return SqliteLiveChangeReceiptStoreOpenResult.Rejected(
                            LiveChangeReceiptStoreFailure.STORAGE_UNAVAILABLE
                        )
                }
            return try {
                val connections = SqliteMutationRecoveryConnections(database).initialize()
                connections.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """CREATE TABLE IF NOT EXISTS live_change_receipt (
                            plan_identity TEXT PRIMARY KEY NOT NULL CHECK(length(plan_identity) = 69 AND plan_identity GLOB 'plan:[0-9a-f]*'),
                            receipt_identity TEXT UNIQUE NOT NULL CHECK(length(receipt_identity) = 72 AND receipt_identity GLOB 'receipt:[0-9a-f]*'),
                            codec_version INTEGER NOT NULL CHECK(codec_version > 0),
                            document TEXT NOT NULL,
                            document_sha256 TEXT NOT NULL CHECK(length(document_sha256) = 64)
                        ) WITHOUT ROWID"""
                        )
                    }
                }
                SqliteLiveChangeReceiptStoreOpenResult.Opened(SqliteLiveChangeReceiptStore(connections))
            } catch (_: Exception) {
                SqliteLiveChangeReceiptStoreOpenResult.Rejected(LiveChangeReceiptStoreFailure.STORAGE_UNAVAILABLE)
            }
        }
    }
}

private data class LiveReceiptRow(
    val receiptIdentity: String,
    val version: Long,
    val document: String,
    val digest: String,
)

private sealed interface LiveReceiptRowObservation {
    data object Missing : LiveReceiptRowObservation

    data class Found(val row: LiveReceiptRow) : LiveReceiptRowObservation
}

private fun Connection.receiptRow(identity: ChangePlanIdentity): LiveReceiptRowObservation =
    prepareStatement(
            "SELECT receipt_identity, codec_version, document, document_sha256 " +
                "FROM live_change_receipt WHERE plan_identity = ?"
        )
        .use { statement ->
            statement.setString(1, identity.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) LiveReceiptRowObservation.Missing
                else
                    LiveReceiptRowObservation.Found(
                        LiveReceiptRow(
                            receiptIdentity = rows.getString("receipt_identity"),
                            version = rows.getLong("codec_version"),
                            document = rows.getString("document"),
                            digest = rows.getString("document_sha256"),
                        )
                    )
            }
        }

private fun planIdentity(receipt: HistoricalLiveAddDeclarationReceipt): ChangePlanIdentity =
    checkNotNull(ChangePlanIdentity.parse("plan:${receipt.plan.planId.value}"))

private fun digest(value: String): String =
    java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

private inline fun <T> storage(rejected: (LiveChangeReceiptStoreFailure) -> T, action: () -> T): T =
    try {
        action()
    } catch (_: SQLException) {
        rejected(LiveChangeReceiptStoreFailure.STORAGE_UNAVAILABLE)
    }

private const val RECEIPT_VERSION_PARAMETER = 3
private const val RECEIPT_DOCUMENT_PARAMETER = 4
private const val RECEIPT_DIGEST_PARAMETER = 5
