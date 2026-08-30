package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.change.contract.HostedAddDeclarationPlanCodec
import io.github.amichne.kast.change.verify.ChangeApplicationIdentity
import io.github.amichne.kast.change.verify.ChangeApplicationIssuance
import io.github.amichne.kast.change.verify.ChangeApplicationLookup
import io.github.amichne.kast.change.verify.ChangePlanIdentity
import io.github.amichne.kast.change.verify.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangePlanLookup
import io.github.amichne.kast.change.verify.ChangeReceiptIdentity
import io.github.amichne.kast.change.verify.ChangeReceiptIssuance
import io.github.amichne.kast.change.verify.DurableChangeAuthority
import io.github.amichne.kast.change.verify.DurableChangeAuthorityFailure
import io.github.amichne.kast.change.verify.PendingChangeVerification
import io.github.amichne.kast.change.verify.VerifiedReceipt
import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

enum class SqliteDurableChangeAuthorityOpenFailure {
    STORAGE_UNAVAILABLE,
}

sealed interface SqliteDurableChangeAuthorityOpenResult {
    data class Opened(val authority: SqliteDurableChangeAuthority) :
        SqliteDurableChangeAuthorityOpenResult

    data class Rejected(val failure: SqliteDurableChangeAuthorityOpenFailure) :
        SqliteDurableChangeAuthorityOpenResult
}

sealed interface SqliteHostedMutationAuthorityOpenResult {
    data class Opened(
        val authority: SqliteDurableChangeAuthority,
        val recoveryJournal: SqliteMutationRecoveryJournal,
    ) : SqliteHostedMutationAuthorityOpenResult

    data class Rejected(val failure: SqliteDurableChangeAuthorityOpenFailure) :
        SqliteHostedMutationAuthorityOpenResult
}

sealed interface HostedDurableMutationAudit {
    data object Clean : HostedDurableMutationAudit
    data class RecoveryRequired(val bindings: Set<MutationPlanBinding>) :
        HostedDurableMutationAudit
    data class Rejected(val failure: DurableChangeAuthorityFailure) :
        HostedDurableMutationAudit
}

/** Durable public plan/application/receipt authority sharing mutation.sqlite with recovery. */
class SqliteDurableChangeAuthority private constructor(
    private val connections: InitializedSqliteMutationRecoveryConnections,
    private val recovery: SqliteMutationRecoveryJournal,
) : DurableChangeAuthority {
    /**
     * Distinguishes an interrupted applied write from one whose public application identity was
     * durably issued. Only the former withdraws plan/apply/verify authority after restart.
     */
    fun auditMutationState(): HostedDurableMutationAudit = storage(
        rejected = { HostedDurableMutationAudit.Rejected(it) },
    ) {
        val rows = connections.use { connection ->
            connection.prepareStatement(
                """SELECT recovery.plan_binding, recovery.stage, application.identity
                    FROM mutation_recovery AS recovery
                    LEFT JOIN hosted_change_application AS application
                      ON application.recovery_binding = recovery.plan_binding
                    WHERE recovery.stage IN ('APPLIED_WRITES_DURABLE', 'RECOVERY_REQUIRED')
                    ORDER BY recovery.plan_binding""",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                DurableMutationAuditRow(
                                    result.getString("plan_binding"),
                                    result.getString("stage"),
                                    result.getString("identity"),
                                ),
                            )
                        }
                    }
                }
            }
        }
        val recoveryRequired = linkedSetOf<MutationPlanBinding>()
        rows.forEach { row ->
            val binding = when (val parsed = MutationPlanBinding.parse(row.binding)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return@storage HostedDurableMutationAudit.Rejected(
                    DurableChangeAuthorityFailure.CORRUPT_RECORD,
                )
            }
            if (row.stage == "RECOVERY_REQUIRED" || row.applicationIdentity == null) {
                recoveryRequired += binding
            } else {
                val identity = ChangeApplicationIdentity.parse(row.applicationIdentity)
                    ?: return@storage HostedDurableMutationAudit.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                when (loadApplication(identity)) {
                    is ChangeApplicationLookup.Found -> Unit
                    ChangeApplicationLookup.Missing,
                    is ChangeApplicationLookup.Rejected,
                    -> return@storage HostedDurableMutationAudit.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                }
            }
        }
        if (recoveryRequired.isEmpty()) HostedDurableMutationAudit.Clean
        else HostedDurableMutationAudit.RecoveryRequired(recoveryRequired)
    }

    fun loadPlanForRecovery(binding: MutationPlanBinding): ChangePlanLookup = storage(
        rejected = { ChangePlanLookup.Rejected(it) },
    ) {
        val identity = connections.use { connection ->
            connection.prepareStatement(
                "SELECT identity FROM hosted_change_plan WHERE plan_id = ?",
            ).use { statement ->
                statement.setString(1, binding.value)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else ChangePlanIdentity.parse(rows.getString("identity"))
                }
            }
        } ?: return@storage ChangePlanLookup.Missing
        loadPlan(identity)
    }

    override fun issuePlan(plan: ChangePlan): ChangePlanIssuance {
        val supported = plan as? AddDeclarationChangePlan
            ?: return ChangePlanIssuance.Rejected(DurableChangeAuthorityFailure.UNSUPPORTED_PLAN)
        val identity = planIdentity(supported)
        val document = HostedAddDeclarationPlanCodec.encode(supported)
        val digest = sha256(document)
        return storage(
            rejected = { ChangePlanIssuance.Rejected(it) },
        ) {
            connections.use { connection ->
                connection.prepareStatement(
                    """INSERT OR IGNORE INTO hosted_change_plan(
                        identity, plan_id, document, document_sha256
                    ) VALUES (?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, identity.value)
                    statement.setString(2, supported.planId.value)
                    statement.setString(3, document)
                    statement.setString(4, digest)
                    statement.executeUpdate()
                }
                when (val stored = connection.planRow(identity)) {
                    null -> ChangePlanIssuance.Rejected(
                        DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE,
                    )
                    else -> if (
                        stored.planId == supported.planId.value &&
                        stored.document == document &&
                        stored.digest == digest
                    ) {
                        ChangePlanIssuance.Issued(identity)
                    } else {
                        ChangePlanIssuance.Rejected(
                            DurableChangeAuthorityFailure.IDENTITY_COLLISION,
                        )
                    }
                }
            }
        }
    }

    override fun loadPlan(identity: ChangePlanIdentity): ChangePlanLookup = storage(
        rejected = { ChangePlanLookup.Rejected(it) },
    ) {
        connections.use { connection ->
            val row = connection.planRow(identity) ?: return@use ChangePlanLookup.Missing
            if (sha256(row.document) != row.digest) {
                return@use ChangePlanLookup.Rejected(DurableChangeAuthorityFailure.CORRUPT_RECORD)
            }
            val plan = when (val decoded = HostedAddDeclarationPlanCodec.decode(row.document)) {
                is Refinement.Refined -> decoded.value
                is Refinement.Rejected -> return@use ChangePlanLookup.Rejected(
                    DurableChangeAuthorityFailure.CORRUPT_RECORD,
                )
            }
            if (
                row.planId != plan.planId.value ||
                planIdentity(plan) != identity
            ) {
                ChangePlanLookup.Rejected(DurableChangeAuthorityFailure.CORRUPT_RECORD)
            } else {
                ChangePlanLookup.Found(plan)
            }
        }
    }

    override fun issueApplication(
        plan: ChangePlan,
        application: AppliedUnverified,
    ): ChangeApplicationIssuance {
        val supported = plan as? AddDeclarationChangePlan
            ?: return ChangeApplicationIssuance.Rejected(
                DurableChangeAuthorityFailure.UNSUPPORTED_PLAN,
            )
        val plannedWrite = supported.writes.entries.singleOrNull()
            ?: return ChangeApplicationIssuance.Rejected(
                DurableChangeAuthorityFailure.CORRUPT_RECORD,
            )
        if (
            application.planId != supported.planId ||
            application.source != plannedWrite.source ||
            application.publication.plannedLease != supported.priorLease ||
            application.publication.plannedState != supported.workspaceState ||
            application.publication.source != plannedWrite.source ||
            application.publication.sourceRoot != plannedWrite.sourceRoot ||
            application.publication.precondition != plannedWrite.precondition
        ) {
            return ChangeApplicationIssuance.Rejected(
                DurableChangeAuthorityFailure.CORRUPT_RECORD,
            )
        }
        if (!application.recoveryIsApplied()) {
            return ChangeApplicationIssuance.Rejected(
                DurableChangeAuthorityFailure.RECOVERY_EVIDENCE_UNAVAILABLE,
            )
        }
        val planIdentity = planIdentity(supported)
        if (loadPlan(planIdentity) !is ChangePlanLookup.Found) {
            return ChangeApplicationIssuance.Rejected(
                DurableChangeAuthorityFailure.CORRUPT_RECORD,
            )
        }
        val identity = applicationIdentity(supported, application)
        val digest = applicationDigest(
            planIdentity,
            supported.planId.value,
            application.postimage.value,
            application.recoveryBinding.value,
            application.priorLease.workspaceRoot.value,
            application.priorLease.generation.value,
            application.publication.applicationState.value,
        )
        return storage(
            rejected = { ChangeApplicationIssuance.Rejected(it) },
        ) {
            connections.use { connection ->
                connection.prepareStatement(
                    """INSERT OR IGNORE INTO hosted_change_application(
                        identity, plan_identity, plan_id, postimage_sha256,
                        recovery_binding, application_root, application_generation,
                        application_source_state, record_digest
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, identity.value)
                    statement.setString(2, planIdentity.value)
                    statement.setString(3, supported.planId.value)
                    statement.setString(4, application.postimage.value)
                    statement.setString(5, application.recoveryBinding.value)
                    statement.setString(6, application.priorLease.workspaceRoot.value)
                    statement.setLong(7, application.priorLease.generation.value)
                    statement.setString(8, application.publication.applicationState.value)
                    statement.setString(9, digest)
                    statement.executeUpdate()
                }
                val row = connection.applicationRow(identity)
                    ?: return@use ChangeApplicationIssuance.Rejected(
                        DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE,
                    )
                if (
                    row.planIdentity == planIdentity.value &&
                    row.planId == supported.planId.value &&
                    row.postimage == application.postimage.value &&
                    row.recoveryBinding == application.recoveryBinding.value &&
                    row.applicationRoot == application.priorLease.workspaceRoot.value &&
                    row.applicationGeneration == application.priorLease.generation.value &&
                    row.applicationSourceState == application.publication.applicationState.value &&
                    row.digest == digest
                ) {
                    ChangeApplicationIssuance.Issued(identity)
                } else {
                    ChangeApplicationIssuance.Rejected(
                        DurableChangeAuthorityFailure.IDENTITY_COLLISION,
                    )
                }
            }
        }
    }

    override fun loadApplication(identity: ChangeApplicationIdentity): ChangeApplicationLookup =
        storage(rejected = { ChangeApplicationLookup.Rejected(it) }) {
            connections.use { connection ->
                val row = connection.applicationRow(identity)
                    ?: return@use ChangeApplicationLookup.Missing
                val planIdentity = ChangePlanIdentity.parse(row.planIdentity)
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                if (
                    applicationDigest(
                        planIdentity,
                        row.planId,
                        row.postimage,
                        row.recoveryBinding,
                        row.applicationRoot,
                        row.applicationGeneration,
                        row.applicationSourceState,
                    ) != row.digest
                ) {
                    return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                }
                val plan = when (val loaded = loadPlan(planIdentity)) {
                    is ChangePlanLookup.Found -> loaded.plan
                    ChangePlanLookup.Missing,
                    is ChangePlanLookup.Rejected,
                    -> return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                }
                val postimage = WorkspaceSourceContentHash.parse(row.postimage).valueOrNull()
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                val binding = MutationPlanBinding.parse(row.recoveryBinding).valueOrNull()
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                val applicationLease = applicationLease(row)
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                val applicationState = WorkspaceStateIdentity.parse(row.applicationSourceState)
                    .valueOrNull()
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                val restored = AppliedUnverified.restore(
                    plan,
                    applicationLease,
                    applicationState,
                    postimage,
                    binding,
                ).valueOrNull()
                    ?: return@use ChangeApplicationLookup.Rejected(
                        DurableChangeAuthorityFailure.CORRUPT_RECORD,
                    )
                if (
                    applicationIdentity(plan, restored) != identity ||
                    !restored.recoveryIsApplied()
                ) {
                    ChangeApplicationLookup.Rejected(DurableChangeAuthorityFailure.CORRUPT_RECORD)
                } else {
                    ChangeApplicationLookup.Found(PendingChangeVerification(plan, restored))
                }
            }
        }

    override fun issueReceipt(receipt: VerifiedReceipt): ChangeReceiptIssuance {
        val identity = receiptIdentity(receipt)
        val canonical = canonicalFields(
            receipt.planId.value,
            receipt.priorLease.workspaceRoot.value,
            receipt.priorLease.generation.value.toString(),
            receipt.resultingWorkspace.root.value,
            receipt.resultingWorkspace.readLease.generation.value.toString(),
            receipt.resultingWorkspace.sourceState.value,
        )
        val digest = sha256(canonical)
        return storage(rejected = { ChangeReceiptIssuance.Rejected(it) }) {
            connections.use { connection ->
                connection.prepareStatement(
                    """INSERT OR IGNORE INTO hosted_change_receipt(
                        identity, plan_id, prior_root, prior_generation, resulting_root,
                        resulting_generation, resulting_state, record_digest
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                ).use { statement ->
                    statement.setString(1, identity.value)
                    statement.setString(2, receipt.planId.value)
                    statement.setString(3, receipt.priorLease.workspaceRoot.value)
                    statement.setLong(4, receipt.priorLease.generation.value)
                    statement.setString(5, receipt.resultingWorkspace.root.value)
                    statement.setLong(6, receipt.resultingWorkspace.readLease.generation.value)
                    statement.setString(7, receipt.resultingWorkspace.sourceState.value)
                    statement.setString(8, digest)
                    statement.executeUpdate()
                }
                val storedDigest = connection.prepareStatement(
                    "SELECT record_digest FROM hosted_change_receipt WHERE identity = ?",
                ).use { statement ->
                    statement.setString(1, identity.value)
                    statement.executeQuery().use { rows ->
                        rows.takeIf(ResultSet::next)?.getString("record_digest")
                    }
                }
                if (storedDigest == digest) {
                    ChangeReceiptIssuance.Issued(identity)
                } else {
                    ChangeReceiptIssuance.Rejected(
                        DurableChangeAuthorityFailure.IDENTITY_COLLISION,
                    )
                }
            }
        }
    }

    private fun AppliedUnverified.recoveryIsApplied(): Boolean = when (
        val loaded = recovery.load(recoveryBinding)
    ) {
        is MutationRecoveryLoadResult.Found -> when (val record = loaded.record) {
            is MutationRecoveryRecord.AppliedWritesDurable ->
                record.binding == recoveryBinding &&
                    record.appliedWrites.sources.singleOrNull()?.value == source.path.value
            else -> false
        }
        is MutationRecoveryLoadResult.Absent,
        is MutationRecoveryLoadResult.Rejected,
        -> false
    }

    companion object {
        fun open(
            location: MutationDatabaseLocation,
        ): SqliteDurableChangeAuthorityOpenResult = when (val opened = openHosted(location)) {
            is SqliteHostedMutationAuthorityOpenResult.Opened ->
                SqliteDurableChangeAuthorityOpenResult.Opened(opened.authority)
            is SqliteHostedMutationAuthorityOpenResult.Rejected ->
                SqliteDurableChangeAuthorityOpenResult.Rejected(opened.failure)
        }

        /** Opens the exact change authority and recovery journal from one initialized proof. */
        fun openHosted(
            location: MutationDatabaseLocation,
        ): SqliteHostedMutationAuthorityOpenResult {
            val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
                ?: return SqliteHostedMutationAuthorityOpenResult.Rejected(
                    SqliteDurableChangeAuthorityOpenFailure.STORAGE_UNAVAILABLE,
                )
            val database = SqliteMutationRecoveryDatabase.admit(path).valueOrNull()
                ?: return SqliteHostedMutationAuthorityOpenResult.Rejected(
                    SqliteDurableChangeAuthorityOpenFailure.STORAGE_UNAVAILABLE,
                )
            return try {
                val connections = SqliteMutationRecoveryConnections(database).initialize()
                connections.initializeAuthority()
                val journal = SqliteMutationRecoveryJournal.retain(connections)
                SqliteHostedMutationAuthorityOpenResult.Opened(
                    SqliteDurableChangeAuthority(connections, journal),
                    journal,
                )
            } catch (_: Exception) {
                SqliteHostedMutationAuthorityOpenResult.Rejected(
                    SqliteDurableChangeAuthorityOpenFailure.STORAGE_UNAVAILABLE,
                )
            }
        }
    }
}

private fun InitializedSqliteMutationRecoveryConnections.initializeAuthority() = use { connection ->
    connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            statement.execute(
                """CREATE TABLE IF NOT EXISTS hosted_change_plan (
                    identity TEXT PRIMARY KEY NOT NULL
                        CHECK(length(identity) = 69 AND identity GLOB 'plan:[0-9a-f]*'),
                    plan_id TEXT NOT NULL UNIQUE CHECK(length(plan_id) = 64),
                    document TEXT NOT NULL,
                    document_sha256 TEXT NOT NULL CHECK(length(document_sha256) = 64)
                ) WITHOUT ROWID""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS hosted_change_application (
                    identity TEXT PRIMARY KEY NOT NULL
                        CHECK(length(identity) = 76 AND identity GLOB 'application:[0-9a-f]*'),
                    plan_identity TEXT NOT NULL REFERENCES hosted_change_plan(identity),
                    plan_id TEXT NOT NULL CHECK(length(plan_id) = 64),
                    postimage_sha256 TEXT NOT NULL CHECK(length(postimage_sha256) = 64),
                    recovery_binding TEXT NOT NULL CHECK(length(recovery_binding) = 64),
                    application_root TEXT NOT NULL CHECK(length(application_root) > 0),
                    application_generation INTEGER NOT NULL CHECK(application_generation >= 0),
                    application_source_state TEXT NOT NULL CHECK(length(application_source_state) > 0),
                    record_digest TEXT NOT NULL CHECK(length(record_digest) = 64),
                    UNIQUE(plan_identity, postimage_sha256)
                ) WITHOUT ROWID""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS hosted_change_receipt (
                    identity TEXT PRIMARY KEY NOT NULL
                        CHECK(length(identity) = 72 AND identity GLOB 'receipt:[0-9a-f]*'),
                    plan_id TEXT NOT NULL CHECK(length(plan_id) = 64),
                    prior_root TEXT NOT NULL,
                    prior_generation INTEGER NOT NULL,
                    resulting_root TEXT NOT NULL,
                    resulting_generation INTEGER NOT NULL,
                    resulting_state TEXT NOT NULL,
                    record_digest TEXT NOT NULL CHECK(length(record_digest) = 64)
                ) WITHOUT ROWID""",
            )
        }
        connection.migrateLegacyApplicationPublicationProof()
        connection.commit()
    } catch (failure: Exception) {
        runCatching { connection.rollback() }
        throw failure
    } finally {
        connection.autoCommit = true
    }
}

/**
 * Refines pre-publication-proof application rows without changing their stable public identity.
 *
 * Every legacy application necessarily used the plan lease because the former issuance boundary
 * rejected any other lease. The migration therefore strengthens those rows with that already
 * proven relationship and incorporates it into the record digest in one SQLite transaction.
 */
private fun Connection.migrateLegacyApplicationPublicationProof() {
    val columns = createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(hosted_change_application)").use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("name"))
            }
        }
    }
    val required = linkedMapOf(
        "application_root" to
            "ALTER TABLE hosted_change_application ADD COLUMN application_root TEXT NOT NULL DEFAULT ''",
        "application_generation" to
            "ALTER TABLE hosted_change_application ADD COLUMN application_generation INTEGER NOT NULL DEFAULT -1",
        "application_source_state" to
            "ALTER TABLE hosted_change_application ADD COLUMN application_source_state TEXT NOT NULL DEFAULT ''",
    )
    val missing = required.keys - columns
    if (missing.isEmpty()) return
    createStatement().use { statement ->
        missing.forEach { column -> statement.execute(checkNotNull(required[column])) }
    }
    val rows = prepareStatement(
        """SELECT application.identity, application.plan_identity, application.plan_id,
            application.postimage_sha256, application.recovery_binding,
            application.record_digest, plan.document, plan.document_sha256
            FROM hosted_change_application AS application
            JOIN hosted_change_plan AS plan ON plan.identity = application.plan_identity""",
    ).use { statement ->
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) {
                    add(
                        LegacyApplicationMigrationRow(
                            result.getString("identity"),
                            result.getString("plan_identity"),
                            result.getString("plan_id"),
                            result.getString("postimage_sha256"),
                            result.getString("recovery_binding"),
                            result.getString("record_digest"),
                            result.getString("document"),
                            result.getString("document_sha256"),
                        ),
                    )
                }
            }
        }
    }
    rows.forEach { row ->
        check(sha256(row.planDocument) == row.planDocumentDigest) {
            "Legacy hosted change plan digest is corrupt"
        }
        val plan = HostedAddDeclarationPlanCodec.decode(row.planDocument).valueOrNull()
            ?: error("Legacy hosted change plan is corrupt")
        val parsedPlanIdentity = ChangePlanIdentity.parse(row.planIdentity)
            ?: error("Legacy hosted change plan identity is corrupt")
        check(
            plan.planId.value == row.planId &&
                planIdentity(plan) == parsedPlanIdentity &&
                row.recoveryBinding == plan.planId.value &&
                row.applicationDigest == legacyApplicationDigest(
                    parsedPlanIdentity,
                    row.planId,
                    row.postimage,
                    row.recoveryBinding,
                )
        ) {
            "Legacy hosted change application is not bound to its plan"
        }
        val digest = applicationDigest(
            parsedPlanIdentity,
            row.planId,
            row.postimage,
            row.recoveryBinding,
            plan.priorLease.workspaceRoot.value,
            plan.priorLease.generation.value,
            plan.workspaceState.value,
        )
        prepareStatement(
            """UPDATE hosted_change_application
                SET application_root = ?, application_generation = ?,
                    application_source_state = ?, record_digest = ?
                WHERE identity = ?""",
        ).use { statement ->
            statement.setString(1, plan.priorLease.workspaceRoot.value)
            statement.setLong(2, plan.priorLease.generation.value)
            statement.setString(3, plan.workspaceState.value)
            statement.setString(4, digest)
            statement.setString(5, row.identity)
            check(statement.executeUpdate() == 1) {
                "Legacy hosted change application moved during migration"
            }
        }
    }
}

private data class LegacyApplicationMigrationRow(
    val identity: String,
    val planIdentity: String,
    val planId: String,
    val postimage: String,
    val recoveryBinding: String,
    val applicationDigest: String,
    val planDocument: String,
    val planDocumentDigest: String,
)

private data class PlanRow(val planId: String, val document: String, val digest: String)

private fun Connection.planRow(identity: ChangePlanIdentity): PlanRow? = prepareStatement(
    "SELECT plan_id, document, document_sha256 FROM hosted_change_plan WHERE identity = ?",
).use { statement ->
    statement.setString(1, identity.value)
    statement.executeQuery().use { rows ->
        if (!rows.next()) null else PlanRow(
            rows.getString("plan_id"),
            rows.getString("document"),
            rows.getString("document_sha256"),
        )
    }
}

private data class ApplicationRow(
    val planIdentity: String,
    val planId: String,
    val postimage: String,
    val recoveryBinding: String,
    val applicationRoot: String,
    val applicationGeneration: Long,
    val applicationSourceState: String,
    val digest: String,
)

private data class DurableMutationAuditRow(
    val binding: String,
    val stage: String,
    val applicationIdentity: String?,
)

private fun Connection.applicationRow(identity: ChangeApplicationIdentity): ApplicationRow? =
    prepareStatement(
        """SELECT plan_identity, plan_id, postimage_sha256, recovery_binding,
            application_root, application_generation, application_source_state, record_digest
            FROM hosted_change_application WHERE identity = ?""",
    ).use { statement ->
        statement.setString(1, identity.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else ApplicationRow(
                rows.getString("plan_identity"),
                rows.getString("plan_id"),
                rows.getString("postimage_sha256"),
                rows.getString("recovery_binding"),
                rows.getString("application_root"),
                rows.getLong("application_generation"),
                rows.getString("application_source_state"),
                rows.getString("record_digest"),
            )
        }
    }

private fun planIdentity(plan: ChangePlan): ChangePlanIdentity = checkNotNull(
    ChangePlanIdentity.parse("plan:${sha256(canonicalFields(plan.planId.value))}"),
)

private fun applicationIdentity(
    plan: ChangePlan,
    application: AppliedUnverified,
): ChangeApplicationIdentity = checkNotNull(
    ChangeApplicationIdentity.parse(
        "application:${sha256(canonicalFields(plan.planId.value, application.postimage.value))}",
    ),
)

private fun receiptIdentity(receipt: VerifiedReceipt): ChangeReceiptIdentity = checkNotNull(
    ChangeReceiptIdentity.parse(
        "receipt:${sha256(
            canonicalFields(
                receipt.planId.value,
                receipt.resultingWorkspace.readLease.generation.value.toString(),
            ),
        )}",
    ),
)

private fun applicationDigest(
    planIdentity: ChangePlanIdentity,
    planId: String,
    postimage: String,
    recoveryBinding: String,
    applicationRoot: String,
    applicationGeneration: Long,
    applicationSourceState: String,
): String = sha256(
    canonicalFields(
        planIdentity.value,
        planId,
        postimage,
        recoveryBinding,
        applicationRoot,
        applicationGeneration.toString(),
        applicationSourceState,
    ),
)

private fun legacyApplicationDigest(
    planIdentity: ChangePlanIdentity,
    planId: String,
    postimage: String,
    recoveryBinding: String,
): String = sha256(canonicalFields(planIdentity.value, planId, postimage, recoveryBinding))

private fun applicationLease(row: ApplicationRow): SemanticReadLease? {
    val path = runCatching { Path.of(row.applicationRoot) }.getOrNull() ?: return null
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(path).valueOrNull() ?: return null
    val generation = EvidenceGeneration.parse(row.applicationGeneration).valueOrNull() ?: return null
    return SemanticReadLease(root, generation)
}

private fun canonicalFields(vararg fields: String): String = buildString {
    fields.forEach { field ->
        append(field.toByteArray(StandardCharsets.UTF_8).size)
        append(':')
        append(field)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private inline fun <Result> storage(
    rejected: (DurableChangeAuthorityFailure) -> Result,
    block: () -> Result,
): Result = try {
    block()
} catch (_: SQLException) {
    rejected(DurableChangeAuthorityFailure.STORAGE_UNAVAILABLE)
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
