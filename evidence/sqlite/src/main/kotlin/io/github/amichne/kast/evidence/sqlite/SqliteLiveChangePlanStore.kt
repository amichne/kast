package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanDecodeFailure
import io.github.amichne.kast.change.contract.LiveChangeApplicationClaim
import io.github.amichne.kast.change.contract.LiveChangeApplicationHistory
import io.github.amichne.kast.change.contract.LiveChangeApplicationStore
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStore
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException

sealed interface SqliteLiveChangePlanStoreOpenResult {
    data class Opened(val store: SqliteLiveChangePlanStore) : SqliteLiveChangePlanStoreOpenResult

    data class Rejected(val failure: LiveChangePlanStoreFailure) : SqliteLiveChangePlanStoreOpenResult
}

/** Separate immutable live-plan table, sharing the existing recovery database connection capability. */
class SqliteLiveChangePlanStore
private constructor(private val connections: InitializedSqliteMutationRecoveryConnections) :
    LiveChangePlanStore, LiveChangeApplicationStore {
    override fun applicationHistory(identity: ChangePlanIdentity): LiveChangeApplicationHistory =
        storage({ LiveChangeApplicationHistory.Rejected(it) }) {
            when (val loaded = loadPlan(identity)) {
                LiveChangePlanLookup.Missing -> return@storage LiveChangeApplicationHistory.Missing
                is LiveChangePlanLookup.Rejected -> return@storage LiveChangeApplicationHistory.Rejected(loaded.failure)
                is LiveChangePlanLookup.Found -> Unit
            }
            connections.use { connection ->
                connection.prepareStatement("SELECT identity FROM live_change_attempt WHERE identity = ?").use {
                    statement ->
                    statement.setString(1, identity.value)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) LiveChangeApplicationHistory.Attempted
                        else LiveChangeApplicationHistory.NeverAttempted
                    }
                }
            }
        }

    override fun claimApplication(identity: ChangePlanIdentity): LiveChangeApplicationClaim =
        storage({ LiveChangeApplicationClaim.Rejected(it) }) {
            when (val loaded = loadPlan(identity)) {
                LiveChangePlanLookup.Missing -> return@storage LiveChangeApplicationClaim.Missing
                is LiveChangePlanLookup.Rejected -> return@storage LiveChangeApplicationClaim.Rejected(loaded.failure)
                is LiveChangePlanLookup.Found -> Unit
            }
            connections.use { connection ->
                connection.prepareStatement("INSERT OR IGNORE INTO live_change_attempt(identity) VALUES (?)").use {
                    statement ->
                    statement.setString(1, identity.value)
                    when (statement.executeUpdate()) {
                        1 -> LiveChangeApplicationClaim.Claimed(identity)
                        0 -> LiveChangeApplicationClaim.AlreadyAttempted
                        else -> LiveChangeApplicationClaim.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
                    }
                }
            }
        }

    override fun issuePlan(plan: LiveAddDeclarationChangePlan): LiveChangePlanIssuance {
        val identity = identity(plan)
        val document = LiveAddDeclarationPlanCodec.encode(plan)
        val digest = digest(document)
        return storage({ LiveChangePlanIssuance.Rejected(it) }) {
            connections.use { connection ->
                connection
                    .prepareStatement(
                        """INSERT OR IGNORE INTO live_change_plan(
                        identity, plan_id, codec_version, document, document_sha256
                    ) VALUES (?, ?, ?, ?, ?)"""
                    )
                    .use { statement ->
                        statement.setString(1, identity.value)
                        statement.setString(2, plan.planId.value)
                        statement.setInt(PLAN_VERSION_PARAMETER, LiveAddDeclarationPlanCodec.VERSION)
                        statement.setString(PLAN_DOCUMENT_PARAMETER, document)
                        statement.setString(PLAN_DIGEST_PARAMETER, digest)
                        statement.executeUpdate()
                    }
                val expected =
                    LivePlanRow(
                        planId = plan.planId.value,
                        version = LiveAddDeclarationPlanCodec.VERSION.toLong(),
                        document = document,
                        digest = digest,
                    )
                when (val observed = connection.livePlanRow(identity)) {
                    LivePlanRowObservation.Missing ->
                        LiveChangePlanIssuance.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
                    is LivePlanRowObservation.Found ->
                        if (observed.row == expected) {
                            LiveChangePlanIssuance.Issued(identity)
                        } else LiveChangePlanIssuance.Rejected(LiveChangePlanStoreFailure.IDENTITY_COLLISION)
                }
            }
        }
    }

    override fun loadPlan(identity: ChangePlanIdentity): LiveChangePlanLookup =
        storage({ LiveChangePlanLookup.Rejected(it) }) {
            connections.use { connection ->
                when (val observed = connection.livePlanRow(identity)) {
                    LivePlanRowObservation.Missing -> LiveChangePlanLookup.Missing
                    is LivePlanRowObservation.Found -> observed.row.decode(identity)
                }
            }
        }

    companion object {
        fun open(location: MutationDatabaseLocation): SqliteLiveChangePlanStoreOpenResult {
            val path =
                prepareHostedDatabasePath(location.valueAtSqliteBoundary())
                    ?: return SqliteLiveChangePlanStoreOpenResult.Rejected(
                        LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE
                    )
            val database =
                when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return SqliteLiveChangePlanStoreOpenResult.Rejected(
                            LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE
                        )
                }
            return try {
                val connections = SqliteMutationRecoveryConnections(database).initialize()
                connections.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """CREATE TABLE IF NOT EXISTS live_change_attempt (
                            identity TEXT PRIMARY KEY NOT NULL REFERENCES live_change_plan(identity)
                        ) WITHOUT ROWID"""
                        )
                        statement.execute(
                            """CREATE TABLE IF NOT EXISTS live_change_plan (
                                identity TEXT PRIMARY KEY NOT NULL
                                    CHECK(length(identity) = 69 AND identity GLOB 'plan:[0-9a-f]*'),
                                plan_id TEXT NOT NULL UNIQUE CHECK(length(plan_id) = 64),
                                codec_version INTEGER NOT NULL CHECK(codec_version > 0),
                                document TEXT NOT NULL,
                                document_sha256 TEXT NOT NULL CHECK(length(document_sha256) = 64)
                            ) WITHOUT ROWID"""
                        )
                    }
                }
                SqliteLiveChangePlanStoreOpenResult.Opened(SqliteLiveChangePlanStore(connections))
            } catch (_: Exception) {
                SqliteLiveChangePlanStoreOpenResult.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
            }
        }
    }
}

private data class LivePlanRow(val planId: String, val version: Long, val document: String, val digest: String)

private fun LivePlanRow.decode(expected: ChangePlanIdentity): LiveChangePlanLookup {
    if (version != LiveAddDeclarationPlanCodec.VERSION.toLong()) {
        return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.VERSION_UNSUPPORTED)
    }
    if (digest(document) != digest) {
        return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.CORRUPT_RECORD)
    }
    val plan =
        when (val restored = LiveAddDeclarationPlanCodec.decode(document)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return LiveChangePlanLookup.Rejected(restored.failure.storageFailure())
        }
    return if (planId != plan.planId.value || identity(plan) != expected) {
        LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.CORRUPT_RECORD)
    } else LiveChangePlanLookup.Found(plan)
}

private fun LiveAddDeclarationPlanDecodeFailure.storageFailure(): LiveChangePlanStoreFailure =
    when (this) {
        LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED -> LiveChangePlanStoreFailure.VERSION_UNSUPPORTED
        LiveAddDeclarationPlanDecodeFailure.MALFORMED,
        LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH,
        LiveAddDeclarationPlanDecodeFailure.IDENTITY_MISMATCH,
        LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE -> LiveChangePlanStoreFailure.CORRUPT_RECORD
    }

private sealed interface LivePlanRowObservation {
    data object Missing : LivePlanRowObservation

    data class Found(val row: LivePlanRow) : LivePlanRowObservation
}

private fun Connection.livePlanRow(identity: ChangePlanIdentity): LivePlanRowObservation =
    prepareStatement(
            "SELECT plan_id, codec_version, document, document_sha256 FROM live_change_plan WHERE identity = ?"
        )
        .use { statement ->
            statement.setString(1, identity.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) LivePlanRowObservation.Missing
                else
                    LivePlanRowObservation.Found(
                        LivePlanRow(
                            planId = rows.getString("plan_id"),
                            version = rows.getLong("codec_version"),
                            document = rows.getString("document"),
                            digest = rows.getString("document_sha256"),
                        )
                    )
            }
        }

private fun identity(plan: LiveAddDeclarationChangePlan): ChangePlanIdentity =
    checkNotNull(ChangePlanIdentity.parse("plan:${plan.planId.value}"))

private fun digest(document: String): String =
    java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(document.toByteArray(StandardCharsets.UTF_8)))

private inline fun <T> storage(rejected: (LiveChangePlanStoreFailure) -> T, operation: () -> T): T =
    try {
        operation()
    } catch (_: SQLException) {
        rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
    }

private const val PLAN_VERSION_PARAMETER = 3
private const val PLAN_DOCUMENT_PARAMETER = 4
private const val PLAN_DIGEST_PARAMETER = 5
