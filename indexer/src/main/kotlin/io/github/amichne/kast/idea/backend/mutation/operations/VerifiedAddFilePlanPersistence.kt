package io.github.amichne.kast.idea.backend.mutation.operations

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.server.change.AdmittedVerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResult
import io.github.amichne.kast.server.change.VerifiedAddFileApplyResultAdmission
import io.github.amichne.kast.server.change.VerifiedAddFileContent
import io.github.amichne.kast.server.change.VerifiedAddFileFailure
import io.github.amichne.kast.server.change.VerifiedAddFileIntent
import io.github.amichne.kast.server.change.VerifiedAddFilePlan
import io.github.amichne.kast.server.change.VerifiedAddFilePlanId
import io.github.amichne.kast.server.change.VerifiedAddFilePlanVersion
import io.github.amichne.kast.server.change.VerifiedAddFileProgress
import io.github.amichne.kast.server.change.VerifiedAddFileReceipt
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryDispositionAction
import io.github.amichne.kast.server.change.VerifiedAddFileRecoveryId
import io.github.amichne.kast.server.change.VerifiedAddFileReconciliationAction
import io.github.amichne.kast.server.change.VerifiedAddFileRefinement
import io.github.amichne.kast.server.change.VerifiedAddFileTargetPath
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class VerifiedAddFilePlanJournal(
    private val workspaceIdentity: WorkspaceIdentity,
) {
    private val livePlans = java.util.concurrent.ConcurrentHashMap<String, PersistedVerifiedAddFilePlan>()
    private val directory = workspaceIdentity.workspaceCacheDirectoryPath.resolve(JOURNAL_DIRECTORY)

    /**
     * Proof transition: VerifiedAddFilePlanId to VerifiedAddFileJournalRead.
     * Re-admits one workspace-scoped serialized plan and lifecycle only after workspace, PlanId,
     * version, target, content, recovery identity, and application postimage agree with the exact
     * compiler plan. Missing, corrupt, and unavailable records are closed journal failures. Raw
     * paths are used only at this metadata boundary.
     */
    fun load(planId: VerifiedAddFilePlanId): VerifiedAddFileJournalRead {
        livePlans[planId.value]?.let { return VerifiedAddFileJournalRead.Loaded(it) }
        val path = directory.resolve("${planId.value}.json")
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.MISSING)
        }
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.CORRUPT)
        }
        val read = try {
            val encoded = Files.newInputStream(path, READ, NOFOLLOW_LINKS)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            JOURNAL_JSON.decodeFromString<DurablePlanRecord>(encoded)
                .readmit(workspaceIdentity, planId)
        } catch (_: SerializationException) {
            VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.CORRUPT)
        } catch (_: IllegalArgumentException) {
            VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.CORRUPT)
        } catch (_: IOException) {
            VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
        }
        return if (read is VerifiedAddFileJournalRead.Loaded) VerifiedAddFileJournalRead.Loaded(livePlans.putIfAbsent(planId.value, read.plan) ?: read.plan) else read
    }

    /**
     * Proof transition: PersistedVerifiedAddFilePlan to VerifiedAddFileJournalWrite.
     * Atomically publishes and fsyncs the exact workspace-scoped plan/lifecycle record before the
     * native operation responds. The closed failure is journal UNAVAILABLE. Raw filesystem access
     * is restricted to this operation-metadata persistence boundary.
     */
    fun store(plan: PersistedVerifiedAddFilePlan): VerifiedAddFileJournalWrite {
        val encoded = try {
            JOURNAL_JSON.encodeToString(plan.toDurableRecord(workspaceIdentity))
        } catch (_: SerializationException) {
            return VerifiedAddFileJournalWrite.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
        }
        try {
            Files.createDirectories(directory)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                return VerifiedAddFileJournalWrite.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
            }
            val temporary = Files.createTempFile(directory, ".verified-add-file-", ".tmp")
            try {
                Files.writeString(temporary, encoded)
                FileChannel.open(temporary, WRITE).use { it.force(true) }
                Files.move(
                    temporary,
                    directory.resolve("${plan.planId.value}.json"),
                    ATOMIC_MOVE,
                    REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (_: IOException) {
            return VerifiedAddFileJournalWrite.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
        } catch (_: SecurityException) {
            return VerifiedAddFileJournalWrite.Rejected(VerifiedAddFileJournalFailure.UNAVAILABLE)
        }
        livePlans.putIfAbsent(plan.planId.value, plan)
        return VerifiedAddFileJournalWrite.Stored
    }
}

@Serializable
private data class DurablePlanRecord(
    val schemaVersion: Int,
    val workspaceRoot: String,
    val planId: String,
    val initialVersion: Long,
    val exact: AddFilePlanResult,
    val lifecycle: DurableLifecycleRecord,
) {
    /**
     * Proof transition: persisted record plus workspace and requested PlanId to journal read.
     *
     * Reconstructs only a strong plan whose complete persisted authority agrees with the owning
     * workspace and requested identity. Every mismatch is a closed CORRUPT journal failure.
     * Raw fields are extracted only at this serialized-record boundary.
     */
    fun readmit(owner: WorkspaceIdentity, requestedId: VerifiedAddFilePlanId): VerifiedAddFileJournalRead {
        if (schemaVersion != JOURNAL_SCHEMA || workspaceRoot != owner.workspaceRoot.value) return corrupt()
        val id = when (val result = VerifiedAddFilePlanId.refine(planId)) {
            is VerifiedAddFileRefinement.Refined -> result.value
            is VerifiedAddFileRefinement.Rejected -> return corrupt()
        }
        val version = when (val result = VerifiedAddFilePlanVersion.refine(initialVersion)) {
            is VerifiedAddFileRefinement.Refined -> result.value
            is VerifiedAddFileRefinement.Rejected -> return corrupt()
        }
        if (id != requestedId || version != wireVersion(0L)) return corrupt()
        val target = when (val result = VerifiedAddFileTargetPath.refine(exact.proof.targetPath.value)) {
            is VerifiedAddFileRefinement.Refined -> result.value
            is VerifiedAddFileRefinement.Rejected -> return corrupt()
        }
        val content = when (val result = VerifiedAddFileContent.refine(exact.proposedContent)) {
            is VerifiedAddFileRefinement.Refined -> result.value
            is VerifiedAddFileRefinement.Rejected -> return corrupt()
        }
        val intent = VerifiedAddFileIntent(owner.workspaceRoot, target, content)
        val planned = when (val result = VerifiedAddFilePlan.admit(intent, exact)) {
            is VerifiedAddFileAdmission.Admitted -> result.value
            is VerifiedAddFileAdmission.Rejected -> return corrupt()
        }
        if (verifiedAddFilePlanId(planned) != id) return corrupt()
        return when (val restored = lifecycle.readmit(planned, id, version)) {
            is DurableLifecycleAdmission.Admitted -> VerifiedAddFileJournalRead.Loaded(
                PersistedVerifiedAddFilePlan(id, version, planned, restored.lifecycle),
            )
            DurableLifecycleAdmission.Rejected -> corrupt()
        }
    }
}

@Serializable
private sealed interface DurableLifecycleRecord {
    @Serializable @SerialName("awaitingApproval")
    data object AwaitingApproval : DurableLifecycleRecord

    @Serializable @SerialName("applyOutcomeUnknown")
    data class ApplyOutcomeUnknown(
        val recoveryId: String,
    ) : DurableLifecycleRecord

    @Serializable @SerialName("recoveryRequired")
    data class RecoveryRequired(
        val application: DurableApplicationRecord,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : DurableLifecycleRecord

    @Serializable @SerialName("reconciliationRequired")
    data class ReconciliationRequired(
        val application: DurableApplicationRecord,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : DurableLifecycleRecord

    @Serializable @SerialName("nonDestructiveReconciliationRequired")
    data class NonDestructiveReconciliationRequired(
        val recoveryId: String,
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileReconciliationAction,
    ) : DurableLifecycleRecord

    @Serializable @SerialName("verified")
    data class Verified(val receipt: DurableReceiptRecord) : DurableLifecycleRecord

    @Serializable @SerialName("rolledBack")
    data class RolledBack(
        val progress: VerifiedAddFileProgress,
        val failure: VerifiedAddFileFailure,
        val action: VerifiedAddFileRecoveryDispositionAction,
    ) : DurableLifecycleRecord
}

@Serializable
private data class DurableApplicationRecord(
    val recoveryId: String,
    val targetPath: AdditionTargetPath,
    val postimageSha256: AdditionPostimageSha256,
)

@Serializable
private data class DurableReceiptRecord(
    val targetPath: AdditionTargetPath,
    val postimageSha256: AdditionPostimageSha256,
    val generation: MutationSemanticGeneration,
    val packageIdentity: AdditionKotlinPackage,
    val declarations: List<AdditionTopLevelDeclaration>,
) {
    fun toReceipt() = VerifiedAddFileReceipt(
        targetPath, postimageSha256, generation, packageIdentity, declarations,
    )
}

/**
 * Proof transition: `DurableLifecycleRecord -> DurableLifecycleAdmission`.
 * Reconstructs only a lifecycle whose strong plan, result matrix, and recovery authority agree.
 * Rejected is the closed failure; raw variants are inspected only at this journal boundary.
 */
private fun DurableLifecycleRecord.readmit(
    planned: VerifiedAddFilePlan,
    planId: VerifiedAddFilePlanId,
    initialVersion: VerifiedAddFilePlanVersion,
): DurableLifecycleAdmission {
    if (this is DurableLifecycleRecord.AwaitingApproval) {
        return DurableLifecycleAdmission.Admitted(PersistedVerifiedAddFileLifecycle.AwaitingApproval)
    }
    if (this is DurableLifecycleRecord.ApplyOutcomeUnknown) {
        val recoveryId = when (val result = refineRecoveryId(recoveryId)) {
            is DurableRecoveryIdAdmission.Admitted -> result.recoveryId
            DurableRecoveryIdAdmission.Rejected -> return DurableLifecycleAdmission.Rejected
        }
        return when (val result = VerifiedAddFileRecoveryPrepared.readmitPersisted(planned, recoveryId)) {
            is VerifiedAddFileProofAdmission.Admitted -> DurableLifecycleAdmission.Admitted(
                PersistedVerifiedAddFileLifecycle.ApplyOutcomeUnknown(result.value),
            )
            is VerifiedAddFileProofAdmission.Rejected -> DurableLifecycleAdmission.Rejected
        }
    }
    if (this is DurableLifecycleRecord.Verified) {
        val value = receipt.toReceipt()
        val proof = planned.exact.proof
        if (
            value.targetPath != proof.targetPath ||
            value.postimageSha256 != proof.postimageSha256 ||
            value.generation.value <= proof.context.requiredGeneration.value ||
            value.packageIdentity != proof.packageIdentity ||
            value.declarations != proof.declarations
        ) return DurableLifecycleAdmission.Rejected
        return admittedLifecycle(VerifiedAddFileApplyResult.Verified(planId, wireVersion(5L), value)) {
            PersistedVerifiedAddFileLifecycle.Terminal.Verified(it as VerifiedAddFileApplyResult.Verified)
        }
    }
    if (this is DurableLifecycleRecord.RolledBack) {
        return admittedLifecycle(
            VerifiedAddFileApplyResult.RolledBack(
                planId, wireVersion(5L), progress.toStage(), progress, failure, action,
            ),
        ) { PersistedVerifiedAddFileLifecycle.Terminal.RolledBack(it as VerifiedAddFileApplyResult.RolledBack) }
    }
    if (this is DurableLifecycleRecord.NonDestructiveReconciliationRequired) {
        val recovery = when (val result = refineRecoveryId(recoveryId)) {
            is DurableRecoveryIdAdmission.Admitted -> result.recoveryId
            DurableRecoveryIdAdmission.Rejected -> return DurableLifecycleAdmission.Rejected
        }
        return admittedLifecycle(
            VerifiedAddFileApplyResult.ReconciliationRequired(
                planId, recovery, initialVersion, progress.toStage(), progress, failure, action,
            ),
        ) {
            PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired(
                it as VerifiedAddFileApplyResult.ReconciliationRequired,
            )
        }
    }
    val applicationRecord = when (this) {
        is DurableLifecycleRecord.RecoveryRequired -> application
        is DurableLifecycleRecord.ReconciliationRequired -> application
    }
    val application = when (val result = applicationRecord.readmit(planned)) {
        is DurableApplicationAdmission.Admitted -> result.application
        DurableApplicationAdmission.Rejected -> return DurableLifecycleAdmission.Rejected
    }
    return when (this) {
        is DurableLifecycleRecord.RecoveryRequired -> admittedLifecycle(
            VerifiedAddFileApplyResult.RecoveryRequired(
                planId, application.recovery.recoveryId, initialVersion,
                progress.toStage(), progress, failure, action,
            ),
        ) { PersistedVerifiedAddFileLifecycle.RecoveryRequired(application, progress, failure, action) }
        is DurableLifecycleRecord.ReconciliationRequired -> admittedLifecycle(
            VerifiedAddFileApplyResult.ReconciliationRequired(
                planId, application.recovery.recoveryId, initialVersion,
                progress.toStage(), progress, failure, action,
            ),
        ) { PersistedVerifiedAddFileLifecycle.ReconciliationRequired(application, progress, failure, action) }
    }
}

/** Proof transition: durable application plus strong plan to closed, exact application admission. */
private fun DurableApplicationRecord.readmit(planned: VerifiedAddFilePlan): DurableApplicationAdmission {
    val recovery = when (val result = refineRecoveryId(recoveryId)) {
        is DurableRecoveryIdAdmission.Admitted -> result.recoveryId
        DurableRecoveryIdAdmission.Rejected -> return DurableApplicationAdmission.Rejected
    }
    val prepared = when (val result = VerifiedAddFileRecoveryPrepared.readmitPersisted(planned, recovery)) {
        is VerifiedAddFileProofAdmission.Admitted -> result.value
        is VerifiedAddFileProofAdmission.Rejected -> return DurableApplicationAdmission.Rejected
    }
    return when (
        val result = AppliedVerifiedAddFile.readmitPersisted(prepared, targetPath, postimageSha256)
    ) {
        is VerifiedAddFileProofAdmission.Admitted -> DurableApplicationAdmission.Admitted(result.value)
        is VerifiedAddFileProofAdmission.Rejected -> DurableApplicationAdmission.Rejected
    }
}

/** Proof transition: wire result to closed lifecycle admission through the finite result matrix. */
private fun admittedLifecycle(
    candidate: VerifiedAddFileApplyResult,
    lifecycle: (VerifiedAddFileApplyResult) -> PersistedVerifiedAddFileLifecycle,
): DurableLifecycleAdmission = when (val result = AdmittedVerifiedAddFileApplyResult.admit(candidate)) {
    is VerifiedAddFileApplyResultAdmission.Admitted ->
        DurableLifecycleAdmission.Admitted(lifecycle(result.value.result))
    is VerifiedAddFileApplyResultAdmission.Rejected -> DurableLifecycleAdmission.Rejected
}

/** Proof transition: raw journal string to typed recovery identity or closed rejection. */
private fun refineRecoveryId(raw: String): DurableRecoveryIdAdmission =
    when (val result = VerifiedAddFileRecoveryId.refine(raw)) {
        is VerifiedAddFileRefinement.Refined -> DurableRecoveryIdAdmission.Admitted(result.value)
        is VerifiedAddFileRefinement.Rejected -> DurableRecoveryIdAdmission.Rejected
    }

private fun PersistedVerifiedAddFilePlan.toDurableRecord(owner: WorkspaceIdentity) = DurablePlanRecord(
    JOURNAL_SCHEMA, owner.workspaceRoot.value, planId.value, initialVersion.value,
    planned.exact, lifecycle.toDurableRecord(),
)

private fun PersistedVerifiedAddFileLifecycle.toDurableRecord(): DurableLifecycleRecord = when (this) {
    PersistedVerifiedAddFileLifecycle.AwaitingApproval -> DurableLifecycleRecord.AwaitingApproval
    is PersistedVerifiedAddFileLifecycle.ApplyOutcomeUnknown ->
        DurableLifecycleRecord.ApplyOutcomeUnknown(recovery.recoveryId.value)
    is PersistedVerifiedAddFileLifecycle.RecoveryRequired -> DurableLifecycleRecord.RecoveryRequired(
        application.toDurableRecord(), progress, failure, action,
    )
    is PersistedVerifiedAddFileLifecycle.ReconciliationRequired ->
        DurableLifecycleRecord.ReconciliationRequired(
            application.toDurableRecord(), progress, failure, action,
        )
    is PersistedVerifiedAddFileLifecycle.NonDestructiveReconciliationRequired ->
        DurableLifecycleRecord.NonDestructiveReconciliationRequired(
            result.recoveryId.value, result.progress, result.failure, result.action,
        )
    is PersistedVerifiedAddFileLifecycle.Terminal.Verified -> DurableLifecycleRecord.Verified(
        DurableReceiptRecord(
            result.receipt.targetPath, result.receipt.postimageSha256, result.receipt.generation,
            result.receipt.packageIdentity, result.receipt.declarations,
        ),
    )
    is PersistedVerifiedAddFileLifecycle.Terminal.RolledBack -> DurableLifecycleRecord.RolledBack(
        result.progress, result.failure, result.action,
    )
}

private fun AppliedVerifiedAddFile.toDurableRecord() = DurableApplicationRecord(
    recovery.recoveryId.value, targetPath, postimageSha256,
)

private fun corrupt() = VerifiedAddFileJournalRead.Rejected(VerifiedAddFileJournalFailure.CORRUPT)

private const val JOURNAL_SCHEMA = 1
private const val JOURNAL_DIRECTORY = "verified-add-file-plans"
private val JOURNAL_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
}
