package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AddDeclarationApplyServiceTest {
    private val fixture = ApplyTestFixture()

    @Test
    fun `exact durable write returns only applied unverified`() {
        val store = InMemoryApplyRecoveryStore()
        val adapter = FakeSourceAdapter(fixture.observed())
        val service = service(store, adapter)

        val result = service.apply(fixture.request())

        val applied = assertInstanceOf(AppliedUnverified::class.java, result)
        assertEquals(fixture.plan.planId, applied.planId)
        assertEquals(fixture.plan.target.file, applied.source)
        assertEquals(fixture.workspace.readLease, applied.priorLease)
        assertInstanceOf(MutationRecoveryRecord.AppliedWritesDurable::class.java, store.current())
        assertEquals(1, adapter.writeCalls)
    }

    @Test
    fun `post durability fault rolls back and records terminal evidence`() {
        val store = InMemoryApplyRecoveryStore()
        val adapter = FakeSourceAdapter(fixture.observed(), WriteMode.FAULT_AFTER_DURABILITY)
        val service = service(store, adapter)

        val result = service.apply(fixture.request())

        assertInstanceOf(AddDeclarationApplyResult.RolledBack::class.java, result)
        assertInstanceOf(MutationRecoveryRecord.RolledBack::class.java, store.current())
        assertEquals(1, adapter.rollbackCalls)
    }

    @Test
    fun `AddFile post durability fault rolls back and records terminal evidence`() {
        val plan = fixture.addFilePlan()
        val store = InMemoryApplyRecoveryStore()
        val adapter = FakeSourceAdapter(
            fixture.absent(plan),
            WriteMode.FAULT_AFTER_DURABILITY,
        )
        val service = service(store, adapter)

        val result = service.apply(fixture.request(plan = plan))

        assertInstanceOf(AddDeclarationApplyResult.RolledBack::class.java, result)
        assertInstanceOf(MutationRecoveryRecord.RolledBack::class.java, store.current())
        assertEquals(1, adapter.rollbackCalls)
    }

    @Test
    fun `rollback rejection remains recovery required`() {
        val store = InMemoryApplyRecoveryStore()
        val adapter = FakeSourceAdapter(
            fixture.observed(),
            WriteMode.FAULT_AFTER_DURABILITY,
            AddDeclarationRollbackResult.Rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED),
        )
        val service = service(store, adapter)

        val result = service.apply(fixture.request())

        assertInstanceOf(AddDeclarationApplyResult.RecoveryRequired::class.java, result)
        assertInstanceOf(MutationRecoveryRecord.RecoveryRequired::class.java, store.current())
    }

    @Test
    fun `writer cannot manufacture applied state without crossing durability barrier`() {
        val store = InMemoryApplyRecoveryStore()
        val adapter = FakeSourceAdapter(fixture.observed(), WriteMode.SKIP_DURABILITY)
        val service = service(store, adapter)

        val result = service.apply(fixture.request())

        assertInstanceOf(AddDeclarationApplyResult.RecoveryRequired::class.java, result)
        assertInstanceOf(MutationRecoveryRecord.PreWriteDurable::class.java, store.current())
    }

    private fun service(
        store: InMemoryApplyRecoveryStore,
        adapter: FakeSourceAdapter,
    ): AddDeclarationApplyService = AddDeclarationApplyService(
        AddDeclarationRecoveryService(store),
        adapter,
        adapter,
        adapter,
    )
}

private enum class WriteMode {
    APPLIED,
    FAULT_AFTER_DURABILITY,
    SKIP_DURABILITY,
}

private class FakeSourceAdapter(
    private val observation: ObservedMutationPrecondition,
    private val mode: WriteMode = WriteMode.APPLIED,
    private val rollbackResult: AddDeclarationRollbackResult = AddDeclarationRollbackResult.RolledBack,
) : AddDeclarationSourceObserver, AddDeclarationSourceWriter, AddDeclarationSourceRollback {
    var writeCalls: Int = 0
    var rollbackCalls: Int = 0

    override fun observe(source: io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity.Workspace): SourceObservationResult =
        SourceObservationResult.Observed(observation)

    override fun write(
        authority: MutationAuthority,
        durability: MutationDurabilityBarrier,
    ): SourceWriteResult {
        writeCalls += 1
        val applied = AppliedSourceWrite.observe(
            authority,
            authority.postimageBytesAtIntellijBoundary(),
            setOf(authority.source.path.value),
        ).refined()
        return when (mode) {
            WriteMode.APPLIED -> when (durability.recordApplied()) {
                MutationDurabilityResult.Durable -> SourceWriteResult.Applied(applied)
                is MutationDurabilityResult.Rejected ->
                    SourceWriteResult.RejectedAfterRollback(SourceWriteFailure.DURABILITY_REJECTED)
            }
            WriteMode.FAULT_AFTER_DURABILITY -> when (durability.recordApplied()) {
                MutationDurabilityResult.Durable ->
                    SourceWriteResult.RecoveryRequired(SourceWriteFailure.SAVE_FAILED)
                is MutationDurabilityResult.Rejected ->
                    SourceWriteResult.RejectedAfterRollback(SourceWriteFailure.DURABILITY_REJECTED)
            }
            WriteMode.SKIP_DURABILITY -> SourceWriteResult.Applied(applied)
        }
    }

    override fun rollback(
        authority: MutationAuthority,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): AddDeclarationRollbackResult {
        rollbackCalls += 1
        return rollbackResult
    }
}

private class InMemoryApplyRecoveryStore : MutationRecoveryEvidenceStore {
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

    fun current(): MutationRecoveryRecord = records.values.single()

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
