package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.apply.AddDeclarationApplyService
import io.github.amichne.kast.change.apply.AddDeclarationSourceObserver
import io.github.amichne.kast.change.apply.AddDeclarationSourceRollback
import io.github.amichne.kast.change.apply.AddDeclarationSourceWriter
import io.github.amichne.kast.change.apply.AppliedSourceWrite
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationDurabilityBarrier
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.apply.SourceWriteResult
import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal fun applyExactMutation(fixture: VerifiedMutationFixture): AppliedUnverified {
    val adapter = ExactWriteAdapter(fixture.observedSource())
    val service = AddDeclarationApplyService(
        AddDeclarationRecoveryService(InMemoryVerificationRecoveryStore()),
        adapter,
        adapter,
        adapter,
    )
    return service.apply(fixture.applyRequest()) as AppliedUnverified
}

private class ExactWriteAdapter(
    private val observation: ObservedMutationSource,
) : AddDeclarationSourceObserver, AddDeclarationSourceWriter, AddDeclarationSourceRollback {
    override fun observe(
        source: SymbolDiscoveryFileIdentity.Workspace,
    ): SourceObservationResult = SourceObservationResult.Observed(observation)

    override fun write(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult {
        val applied = AppliedSourceWrite.observe(
            authority,
            authority.postimageBytesAtIntellijBoundary(),
            setOf(authority.source.path.value),
        ).refined()
        return when (durability.recordApplied()) {
            MutationDurabilityResult.Durable -> SourceWriteResult.Applied(applied)
            is MutationDurabilityResult.Rejected ->
                SourceWriteResult.RejectedAfterRollback(SourceWriteFailure.DURABILITY_REJECTED)
        }
    }

    override fun rollback(
        authority: MutationAuthority,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): AddDeclarationRollbackResult = AddDeclarationRollbackResult.RolledBack
}

private class InMemoryVerificationRecoveryStore : MutationRecoveryEvidenceStore {
    private val records = linkedMapOf<String, MutationRecoveryRecord>()

    override fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = persist(record)

    override fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> =
        transition(prior, record)

    override fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> = transition(prior, record)

    override fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult =
        records[binding.value]?.let(MutationRecoveryLoadResult::Found)
            ?: MutationRecoveryLoadResult.Absent(binding)

    private fun <Record : MutationRecoveryRecord> persist(
        record: Record,
    ): MutationRecoveryPersistResult<Record> {
        records[record.binding.value] = record
        return MutationRecoveryPersistResult.Durable(record)
    }

    private fun <Record : MutationRecoveryRecord> transition(
        prior: MutationRecoveryRecord,
        record: Record,
    ): MutationRecoveryPersistResult<Record> =
        if (records[prior.binding.value]?.digest == prior.digest) {
            persist(record)
        } else {
            MutationRecoveryPersistResult.Rejected(
                MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH,
            )
        }
}
