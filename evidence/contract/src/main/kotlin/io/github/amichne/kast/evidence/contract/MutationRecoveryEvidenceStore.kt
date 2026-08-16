package io.github.amichne.kast.evidence.contract

enum class MutationRecoveryEvidenceFailure {
    STORAGE_UNAVAILABLE,
    PLAN_BINDING_COLLISION,
    PRIOR_STATE_MISMATCH,
    CORRUPT_RECORD,
}

sealed interface MutationRecoveryPersistResult<out Record : MutationRecoveryRecord> {
    data class Durable<Record : MutationRecoveryRecord>(
        val record: Record,
    ) : MutationRecoveryPersistResult<Record>

    data class Rejected(
        val failure: MutationRecoveryEvidenceFailure,
    ) : MutationRecoveryPersistResult<Nothing>
}

sealed interface MutationRecoveryLoadResult {
    data class Found(
        val record: MutationRecoveryRecord,
    ) : MutationRecoveryLoadResult

    data class Absent(
        val binding: MutationPlanBinding,
    ) : MutationRecoveryLoadResult

    data class Rejected(
        val failure: MutationRecoveryEvidenceFailure,
    ) : MutationRecoveryLoadResult
}

/** Narrow durable evidence port for exact mutation recovery state. */
interface MutationRecoveryEvidenceStore {
    /**
     * Proof transition: `PreWriteDurable -> MutationRecoveryPersistResult<PreWriteDurable>`.
     *
     * A durable result proves exact pre-write bytes committed before any applied write exists.
     * [MutationRecoveryEvidenceFailure] is the closed expected failure. JDBC and raw columns remain
     * inside the persistence adapter.
     */
    fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable>

    /**
     * Proof transition: `(PreWriteDurable, AppliedWritesDurable) ->
     * MutationRecoveryPersistResult<AppliedWritesDurable>`.
     *
     * A durable result proves an atomic exact-prior transition to the applied write set.
     * [MutationRecoveryEvidenceFailure] is the closed expected failure. JDBC and raw columns remain
     * inside the persistence adapter.
     */
    fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable>

    /**
     * Proof transition: `(AppliedWritesDurable, Terminal) ->
     * MutationRecoveryPersistResult<Terminal>`.
     *
     * A durable result proves an atomic exact-prior transition to `RolledBack` or
     * `RecoveryRequired`. [MutationRecoveryEvidenceFailure] is the closed expected failure. JDBC
     * and raw columns remain inside the persistence adapter.
     */
    fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record>

    /**
     * Proof transition: `MutationPlanBinding -> MutationRecoveryLoadResult`.
     *
     * A found result carries one fully re-derived digest chain; absence and expected load failures
     * are closed by [MutationRecoveryLoadResult]. Raw row extraction remains inside the adapter.
     */
    fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult
}
