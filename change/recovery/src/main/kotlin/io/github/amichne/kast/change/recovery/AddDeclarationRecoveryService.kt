package io.github.amichne.kast.change.recovery

import io.github.amichne.kast.evidence.contract.AppliedRecoveryWriteSet
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPreparation
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.PlannedRecoveryWrite
import io.github.amichne.kast.evidence.contract.RecoveryRequirement
import io.github.amichne.kast.kernel.Refinement

sealed interface AddDeclarationRecoveryOperationFailure {
    data object ContractViolation : AddDeclarationRecoveryOperationFailure

    data class Evidence(
        val failure: MutationRecoveryEvidenceFailure,
    ) : AddDeclarationRecoveryOperationFailure
}

class PreparedAddDeclarationRecovery internal constructor(
    val input: AddDeclarationRecoveryPreparation,
    val record: MutationRecoveryRecord.PreWriteDurable,
)

class AppliedAddDeclarationRecovery internal constructor(
    val prepared: PreparedAddDeclarationRecovery,
    val record: MutationRecoveryRecord.AppliedWritesDurable,
)

sealed interface PrepareAddDeclarationRecoveryResult {
    data class Prepared(
        val recovery: PreparedAddDeclarationRecovery,
    ) : PrepareAddDeclarationRecoveryResult

    data class Rejected(
        val failure: AddDeclarationRecoveryOperationFailure,
    ) : PrepareAddDeclarationRecoveryResult
}

sealed interface RecordAppliedAddDeclarationResult {
    data class Recorded(
        val recovery: AppliedAddDeclarationRecovery,
    ) : RecordAppliedAddDeclarationResult

    data class Rejected(
        val failure: AddDeclarationRecoveryOperationFailure,
    ) : RecordAppliedAddDeclarationResult
}

enum class AddDeclarationRollbackFailure {
    TARGET_UNAVAILABLE,
    CONTENT_DIVERGED,
    WRITE_REJECTED,
}

sealed interface AddDeclarationRollbackResult {
    data object RolledBack : AddDeclarationRollbackResult

    data class Rejected(
        val failure: AddDeclarationRollbackFailure,
    ) : AddDeclarationRollbackResult
}

/** Narrow capability that performs only rollback of an exact persisted applied write set. */
fun interface AddDeclarationRollbackPort {
    /**
     * Proof transition: `AppliedWritesDurable -> AddDeclarationRollbackResult`.
     *
     * A rolled-back result establishes restoration of every exact persisted preimage.
     * [AddDeclarationRollbackFailure] is the closed expected failure. Raw bytes may be extracted
     * only inside the physical recovery adapter implementing this boundary.
     */
    fun rollback(record: MutationRecoveryRecord.AppliedWritesDurable): AddDeclarationRollbackResult
}

sealed interface PriorStateEvidence {
    data class Absent(
        val binding: MutationPlanBinding,
    ) : PriorStateEvidence

    data class DurablePreWrite(
        val record: MutationRecoveryRecord.PreWriteDurable,
    ) : PriorStateEvidence
}

enum class UndurableRecoveryRequirement {
    EVIDENCE_UNAVAILABLE,
    EVIDENCE_CORRUPT,
    TERMINAL_PERSISTENCE_FAILED,
}

sealed interface RecoveryRequiredEvidence {
    data class Durable(
        val record: MutationRecoveryRecord.RecoveryRequired,
    ) : RecoveryRequiredEvidence

    data class Undurable(
        val binding: MutationPlanBinding,
        val requirement: UndurableRecoveryRequirement,
    ) : RecoveryRequiredEvidence
}

sealed interface AddDeclarationRecoveryOutcome {
    data class PriorState(
        val evidence: PriorStateEvidence,
    ) : AddDeclarationRecoveryOutcome

    data class RolledBack(
        val record: MutationRecoveryRecord.RolledBack,
    ) : AddDeclarationRecoveryOutcome

    data class RecoveryRequired(
        val evidence: RecoveryRequiredEvidence,
    ) : AddDeclarationRecoveryOutcome
}

/** Host-neutral coordinator for durable AddDeclaration recovery evidence. */
class AddDeclarationRecoveryService(
    private val evidence: MutationRecoveryEvidenceStore,
) {
    /**
     * Proof transition: `AddDeclarationRecoveryPreparation ->
     * PrepareAddDeclarationRecoveryResult`.
     *
     * A prepared result establishes durable exact pre-write evidence before any applied write can
     * be represented. Expected failures are closed by [AddDeclarationRecoveryOperationFailure].
     * Raw bytes and persistence handles remain outside this service.
     */
    fun prepare(input: AddDeclarationRecoveryPreparation): PrepareAddDeclarationRecoveryResult {
        val preparation = when (val admitted = MutationRecoveryPreparation.admit(
            input.binding,
            listOf(PlannedRecoveryWrite(input.source, input.preimage)),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return PrepareAddDeclarationRecoveryResult.Rejected(
                AddDeclarationRecoveryOperationFailure.ContractViolation,
            )
        }
        val record = MutationRecoveryRecord.prepare(preparation)
        return when (val persisted = evidence.prepare(record)) {
            is MutationRecoveryPersistResult.Durable ->
                PrepareAddDeclarationRecoveryResult.Prepared(
                    PreparedAddDeclarationRecovery(input, persisted.record),
                )
            is MutationRecoveryPersistResult.Rejected ->
                PrepareAddDeclarationRecoveryResult.Rejected(
                    AddDeclarationRecoveryOperationFailure.Evidence(persisted.failure),
                )
        }
    }

    /**
     * Proof transition: `PreparedAddDeclarationRecovery -> RecordAppliedAddDeclarationResult`.
     *
     * A recorded result establishes the exact planned singleton write set durably chained to its
     * pre-write record. Expected failures are closed by [AddDeclarationRecoveryOperationFailure].
     * This transition performs no source write and extracts no raw content.
     */
    fun recordApplied(
        prepared: PreparedAddDeclarationRecovery,
    ): RecordAppliedAddDeclarationResult {
        val writeSet = when (val admitted = AppliedRecoveryWriteSet.admit(
            prepared.record.preparation.plannedWrites,
            prepared.record.preparation.plannedWrites.map(PlannedRecoveryWrite::source),
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return RecordAppliedAddDeclarationResult.Rejected(
                AddDeclarationRecoveryOperationFailure.ContractViolation,
            )
        }
        val record = when (val transitioned = MutationRecoveryRecord.recordApplied(
            prepared.record,
            writeSet,
        )) {
            is Refinement.Refined -> transitioned.value
            is Refinement.Rejected -> return RecordAppliedAddDeclarationResult.Rejected(
                AddDeclarationRecoveryOperationFailure.ContractViolation,
            )
        }
        return when (val persisted = evidence.recordApplied(prepared.record, record)) {
            is MutationRecoveryPersistResult.Durable ->
                RecordAppliedAddDeclarationResult.Recorded(
                    AppliedAddDeclarationRecovery(prepared, persisted.record),
                )
            is MutationRecoveryPersistResult.Rejected ->
                RecordAppliedAddDeclarationResult.Rejected(
                    AddDeclarationRecoveryOperationFailure.Evidence(persisted.failure),
                )
        }
    }

    /**
     * Proof transition: `(MutationPlanBinding, AddDeclarationRollbackPort) ->
     * AddDeclarationRecoveryOutcome`.
     *
     * Resolves only to prior state, durably `RolledBack`, or explicitly `RecoveryRequired`.
     * Evidence absence, corruption, and unavailability never become mutation success. Raw
     * preimage extraction is delegated only to the supplied rollback capability.
     */
    fun recover(
        binding: MutationPlanBinding,
        rollback: AddDeclarationRollbackPort,
    ): AddDeclarationRecoveryOutcome = when (val loaded = evidence.load(binding)) {
        is MutationRecoveryLoadResult.Absent -> AddDeclarationRecoveryOutcome.PriorState(
            PriorStateEvidence.Absent(loaded.binding),
        )
        is MutationRecoveryLoadResult.Rejected -> AddDeclarationRecoveryOutcome.RecoveryRequired(
            RecoveryRequiredEvidence.Undurable(binding, loaded.failure.toRequirement()),
        )
        is MutationRecoveryLoadResult.Found -> recoverFound(loaded.record, rollback)
    }

    private fun recoverFound(
        record: MutationRecoveryRecord,
        rollback: AddDeclarationRollbackPort,
    ): AddDeclarationRecoveryOutcome = when (record) {
        is MutationRecoveryRecord.PreWriteDurable -> AddDeclarationRecoveryOutcome.PriorState(
            PriorStateEvidence.DurablePreWrite(record),
        )
        is MutationRecoveryRecord.AppliedWritesDurable -> recoverApplied(record, rollback)
        is MutationRecoveryRecord.RolledBack -> AddDeclarationRecoveryOutcome.RolledBack(record)
        is MutationRecoveryRecord.RecoveryRequired ->
            AddDeclarationRecoveryOutcome.RecoveryRequired(
                RecoveryRequiredEvidence.Durable(record),
            )
    }

    private fun recoverApplied(
        applied: MutationRecoveryRecord.AppliedWritesDurable,
        rollback: AddDeclarationRollbackPort,
    ): AddDeclarationRecoveryOutcome = when (rollback.rollback(applied)) {
        AddDeclarationRollbackResult.RolledBack -> {
            val terminal = MutationRecoveryRecord.rolledBack(applied)
            when (val persisted = evidence.recordTerminal(applied, terminal)) {
                is MutationRecoveryPersistResult.Durable ->
                    AddDeclarationRecoveryOutcome.RolledBack(persisted.record)
                is MutationRecoveryPersistResult.Rejected -> undurableTerminal(applied.binding)
            }
        }
        is AddDeclarationRollbackResult.Rejected -> {
            val terminal = MutationRecoveryRecord.recoveryRequired(
                applied,
                RecoveryRequirement.ROLLBACK_REJECTED,
            )
            when (val persisted = evidence.recordTerminal(applied, terminal)) {
                is MutationRecoveryPersistResult.Durable ->
                    AddDeclarationRecoveryOutcome.RecoveryRequired(
                        RecoveryRequiredEvidence.Durable(persisted.record),
                    )
                is MutationRecoveryPersistResult.Rejected -> undurableTerminal(applied.binding)
            }
        }
    }

    private fun undurableTerminal(
        binding: MutationPlanBinding,
    ): AddDeclarationRecoveryOutcome.RecoveryRequired =
        AddDeclarationRecoveryOutcome.RecoveryRequired(
            RecoveryRequiredEvidence.Undurable(
                binding,
                UndurableRecoveryRequirement.TERMINAL_PERSISTENCE_FAILED,
            ),
        )

    private fun MutationRecoveryEvidenceFailure.toRequirement(): UndurableRecoveryRequirement =
        when (this) {
            MutationRecoveryEvidenceFailure.CORRUPT_RECORD ->
                UndurableRecoveryRequirement.EVIDENCE_CORRUPT
            MutationRecoveryEvidenceFailure.STORAGE_UNAVAILABLE,
            MutationRecoveryEvidenceFailure.PLAN_BINDING_COLLISION,
            MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH,
                -> UndurableRecoveryRequirement.EVIDENCE_UNAVAILABLE
        }
}
