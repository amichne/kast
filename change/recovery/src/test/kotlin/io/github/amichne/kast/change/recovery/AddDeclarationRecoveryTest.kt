package io.github.amichne.kast.change.recovery

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AddDeclarationRecoveryTest {
    @Test
    fun `pre-write evidence is durable before an applied write can exist`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val service = AddDeclarationRecoveryService(store)
        val request = request()

        val prepared = assertInstanceOf(
            PrepareAddDeclarationRecoveryResult.Prepared::class.java,
            service.prepare(request),
        ).recovery
        assertInstanceOf(MutationRecoveryRecord.PreWriteDurable::class.java, store.current())
        assertEquals(request.planId, prepared.input.planId)
        assertEquals(request.expectedContent, prepared.input.expectedContent)

        val applied = assertInstanceOf(
            RecordAppliedAddDeclarationResult.Recorded::class.java,
            service.recordApplied(prepared),
        ).recovery
        assertInstanceOf(MutationRecoveryRecord.AppliedWritesDurable::class.java, store.current())
        assertEquals(prepared.record.digest, applied.record.priorDigest)
    }

    @Test
    fun `wrong before image fails closed without persistence`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val exact = request()
        val mismatched = AddDeclarationRecoveryPreparation.admit(
            exact.planId,
            exact.source,
            exact.expectedContent,
            RecoveryPreimage.fromBoundary("changed".toByteArray(StandardCharsets.UTF_8)),
        )
        assertEquals(
            AddDeclarationRecoveryPreparationFailure.PREIMAGE_MISMATCH,
            (mismatched as Refinement.Rejected).failure,
        )
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `recovery resolves only to prior state rolled back or recovery required`() {
        val priorStore = InMemoryMutationRecoveryEvidenceStore()
        val priorService = AddDeclarationRecoveryService(priorStore)
        val prepared = priorService.prepare(request()).prepared()
        assertInstanceOf(
            AddDeclarationRecoveryOutcome.PriorState::class.java,
            priorService.recover(prepared.record.binding) { error("rollback must not run") },
        )

        val rollbackStore = InMemoryMutationRecoveryEvidenceStore()
        val rollbackService = AddDeclarationRecoveryService(rollbackStore)
        val applied = rollbackService.recordApplied(
            rollbackService.prepare(request()).prepared(),
        ).recorded()
        val rolledBack = rollbackService.recover(applied.record.binding) {
            assertEquals(
                "before",
                String(
                    it.preparation.plannedWrites.single().preimage.decodeAtRecoveryBoundary(),
                    StandardCharsets.UTF_8,
                ),
            )
            AddDeclarationRollbackResult.RolledBack
        }
        assertInstanceOf(AddDeclarationRecoveryOutcome.RolledBack::class.java, rolledBack)
        assertInstanceOf(MutationRecoveryRecord.RolledBack::class.java, rollbackStore.current())

        val requiredStore = InMemoryMutationRecoveryEvidenceStore()
        val requiredService = AddDeclarationRecoveryService(requiredStore)
        val unresolved = requiredService.recordApplied(
            requiredService.prepare(request()).prepared(),
        ).recorded()
        val required = requiredService.recover(unresolved.record.binding) {
            AddDeclarationRollbackResult.Rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
        }
        assertInstanceOf(AddDeclarationRecoveryOutcome.RecoveryRequired::class.java, required)
        assertInstanceOf(
            MutationRecoveryRecord.RecoveryRequired::class.java,
            requiredStore.current(),
        )
    }

    @Test
    fun `corrupt evidence cannot be mistaken for success`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val service = AddDeclarationRecoveryService(store)
        val binding = request().binding
        store.loadFailure = MutationRecoveryEvidenceFailure.CORRUPT_RECORD

        val outcome = service.recover(binding) { AddDeclarationRollbackResult.RolledBack }

        val required = assertInstanceOf(
            AddDeclarationRecoveryOutcome.RecoveryRequired::class.java,
            outcome,
        )
        assertInstanceOf(RecoveryRequiredEvidence.Undurable::class.java, required.evidence)
    }

    private fun request(): AddDeclarationRecoveryPreparation {
        val content = "before"
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return AddDeclarationRecoveryPreparation.admit(
            planId = AddDeclarationPlanId.parse("a".repeat(64)).refined(),
            source = RecoverySourcePath.parse(
                "/workspace/app/src/main/kotlin/sample/Service.kt",
            ).refined(),
            expectedContent = WorkspaceSourceContentHash.parse(sha256(bytes)).refined(),
            preimage = RecoveryPreimage.fromBoundary(bytes),
        ).refined()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

private class InMemoryMutationRecoveryEvidenceStore : MutationRecoveryEvidenceStore {
    val records = linkedMapOf<String, MutationRecoveryRecord>()
    var loadFailure: MutationRecoveryEvidenceFailure? = null

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

    override fun load(binding: io.github.amichne.kast.evidence.contract.MutationPlanBinding): MutationRecoveryLoadResult {
        loadFailure?.let { return MutationRecoveryLoadResult.Rejected(it) }
        return records[binding.value]?.let(MutationRecoveryLoadResult::Found)
            ?: MutationRecoveryLoadResult.Absent(binding)
    }

    fun current(): MutationRecoveryRecord = records.values.single()

    private fun <T : MutationRecoveryRecord> persist(record: T): MutationRecoveryPersistResult<T> {
        records[record.binding.value] = record
        return MutationRecoveryPersistResult.Durable(record)
    }

    private fun <T : MutationRecoveryRecord> transition(
        prior: MutationRecoveryRecord,
        record: T,
    ): MutationRecoveryPersistResult<T> = if (records[prior.binding.value]?.digest == prior.digest) {
        persist(record)
    } else {
        MutationRecoveryPersistResult.Rejected(
            MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH,
        )
    }
}

private fun PrepareAddDeclarationRecoveryResult.prepared(): PreparedAddDeclarationRecovery =
    (this as PrepareAddDeclarationRecoveryResult.Prepared).recovery

private fun RecordAppliedAddDeclarationResult.recorded(): AppliedAddDeclarationRecovery =
    (this as RecordAppliedAddDeclarationResult.Recorded).recovery

private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error(failure.toString())
}
