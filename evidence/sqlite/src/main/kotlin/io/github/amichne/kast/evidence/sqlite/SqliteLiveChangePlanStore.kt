package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.contract.ChangePlanIdentity
import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanCodec
import io.github.amichne.kast.change.contract.LiveAddDeclarationPlanDecodeFailure
import io.github.amichne.kast.change.contract.LiveChangeApplicationClaim
import io.github.amichne.kast.change.contract.LiveChangeApplicationHistory
import io.github.amichne.kast.change.contract.LiveChangeApplicationStore
import io.github.amichne.kast.change.contract.LiveChangePlan
import io.github.amichne.kast.change.contract.LiveChangePlanIssuance
import io.github.amichne.kast.change.contract.LiveChangePlanLookup
import io.github.amichne.kast.change.contract.LiveChangePlanStore
import io.github.amichne.kast.change.contract.LiveChangePlanStoreFailure
import io.github.amichne.kast.change.contract.LiveReplaceBodyChangePlan
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanCodec
import io.github.amichne.kast.change.contract.LiveReplaceBodyPlanDecodeFailure
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

    override fun issuePlan(plan: LiveChangePlan): LiveChangePlanIssuance {
        val identity = identity(plan)
        val document =
            when (plan) {
                is LiveAddDeclarationChangePlan -> LiveAddDeclarationPlanCodec.encode(plan)
                is LiveReplaceBodyChangePlan -> LiveReplaceBodyPlanCodec.encode(plan)
            }
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
                        statement.setInt(PLAN_VERSION_PARAMETER, LIVE_PLAN_STORAGE_VERSION)
                        statement.setString(PLAN_DOCUMENT_PARAMETER, document)
                        statement.setString(PLAN_DIGEST_PARAMETER, digest)
                        statement.executeUpdate()
                    }
                val expected =
                    LivePlanRow(
                        planId = plan.planId.value,
                        version = LIVE_PLAN_STORAGE_VERSION.toLong(),
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
                connection.loadLiveChangePlan(identity)
            }
        }

    companion object {
        internal fun retain(
            connections: InitializedSqliteMutationRecoveryConnections
        ): SqliteLiveChangePlanStoreOpenResult =
            try {
                connections.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(LIVE_CHANGE_ATTEMPT_SCHEMA)
                        statement.execute(LIVE_CHANGE_PLAN_SCHEMA)
                    }
                }

                SqliteLiveChangePlanStoreOpenResult.Opened(SqliteLiveChangePlanStore(connections))
            } catch (_: java.sql.SQLException) {
                SqliteLiveChangePlanStoreOpenResult.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
            }

        fun open(location: MutationDatabaseLocation): SqliteLiveChangePlanStoreOpenResult {
            val path =
                when (val result = admitHostedDatabasePath(location.valueAtSqliteBoundary())) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected ->
                        return SqliteLiveChangePlanStoreOpenResult.Rejected(
                            LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE
                        )
                }
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
                retain(connections)
            } catch (_: Exception) {
                SqliteLiveChangePlanStoreOpenResult.Rejected(LiveChangePlanStoreFailure.STORAGE_UNAVAILABLE)
            }
        }
    }
}

private data class LivePlanRow(val planId: String, val version: Long, val document: String, val digest: String)

private fun LivePlanRow.decode(expected: ChangePlanIdentity): LiveChangePlanLookup {
    if (version != LIVE_PLAN_STORAGE_VERSION.toLong()) {
        return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.VERSION_UNSUPPORTED)
    }
    if (digest(document) != digest) {
        return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.CORRUPT_RECORD)
    }
    val format =
        try {
            Json { ignoreUnknownKeys = true }.decodeFromString<StoredLivePlanHeader>(document).format
        } catch (_: SerializationException) {
            return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.CORRUPT_RECORD)
        }
    val plan: LiveChangePlan =
        when (format) {
            "LIVE_ADD_DECLARATION" ->
                when (val restored = LiveAddDeclarationPlanCodec.decode(document)) {
                    is Refinement.Refined -> restored.value
                    is Refinement.Rejected -> return LiveChangePlanLookup.Rejected(restored.failure.storageFailure())
                }
            "LIVE_REPLACE_BODY" ->
                when (val restored = LiveReplaceBodyPlanCodec.decode(document)) {
                    is Refinement.Refined -> restored.value
                    is Refinement.Rejected -> return LiveChangePlanLookup.Rejected(restored.failure.storageFailure())
                }
            else -> return LiveChangePlanLookup.Rejected(LiveChangePlanStoreFailure.VERSION_UNSUPPORTED)
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

private fun LiveReplaceBodyPlanDecodeFailure.storageFailure(): LiveChangePlanStoreFailure =
    if (this == LiveReplaceBodyPlanDecodeFailure.VERSION_UNSUPPORTED) LiveChangePlanStoreFailure.VERSION_UNSUPPORTED
    else LiveChangePlanStoreFailure.CORRUPT_RECORD

@Serializable private data class StoredLivePlanHeader(val format: String)

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

/** Reuses the complete stored-plan decoder without initializing or mutating the database. */
internal fun Connection.loadLiveChangePlan(identity: ChangePlanIdentity): LiveChangePlanLookup =
    when (val observed = livePlanRow(identity)) {
        LivePlanRowObservation.Missing -> LiveChangePlanLookup.Missing
        is LivePlanRowObservation.Found -> observed.row.decode(identity)
    }

private fun identity(plan: LiveChangePlan): ChangePlanIdentity =
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

/** Row envelope version is independent of each embedded plan document codec. */
private const val LIVE_PLAN_STORAGE_VERSION = 1

internal const val LIVE_CHANGE_ATTEMPT_SCHEMA =
    """CREATE TABLE IF NOT EXISTS live_change_attempt (
                            identity TEXT PRIMARY KEY NOT NULL REFERENCES live_change_plan(identity)
                        ) WITHOUT ROWID"""

internal const val LIVE_CHANGE_PLAN_SCHEMA =
    """CREATE TABLE IF NOT EXISTS live_change_plan (
                                identity TEXT PRIMARY KEY NOT NULL
                                    CHECK(length(identity) = 69 AND identity GLOB 'plan:[0-9a-f]*'),
                                plan_id TEXT NOT NULL UNIQUE CHECK(length(plan_id) = 64),
                                codec_version INTEGER NOT NULL CHECK(codec_version > 0),
                                document TEXT NOT NULL,
                                document_sha256 TEXT NOT NULL CHECK(length(document_sha256) = 64)
                            ) WITHOUT ROWID"""
