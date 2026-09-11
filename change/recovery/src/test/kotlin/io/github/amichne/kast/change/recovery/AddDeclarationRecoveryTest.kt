package io.github.amichne.kast.change.recovery

import io.github.amichne.kast.change.contract.AddDeclarationPlanId
import io.github.amichne.kast.change.contract.PlannedSourcePrecondition
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddDeclarationRecoveryTest {
    @Test
    fun `pre-write evidence is durable before an applied write can exist`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val service = AddDeclarationRecoveryService(store)
        val request = request()

        val prepared =
            assertInstanceOf(
                    PrepareAddDeclarationRecoveryResult.Prepared::class.java,
                    service.prepare(request),
                )
                .recovery
        assertInstanceOf(MutationRecoveryRecord.PreWriteDurable::class.java, store.current())
        assertEquals(request.planId, prepared.input.planId)
        assertEquals(request.precondition, prepared.input.precondition)

        val applied =
            assertInstanceOf(
                    RecordAppliedAddDeclarationResult.Recorded::class.java,
                    service.recordApplied(prepared),
                )
                .recovery
        assertInstanceOf(MutationRecoveryRecord.AppliedWritesDurable::class.java, store.current())
        assertEquals(prepared.record.digest, applied.record.priorDigest)
    }

    @Test
    fun `wrong before image fails closed without persistence`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val exact = request()
        val mismatched =
            AddDeclarationRecoveryPreparation.admit(
                exact.planId,
                exact.source,
                exact.precondition,
                RecoveryPreimage.fromBoundary("changed".toByteArray(StandardCharsets.UTF_8)),
            )
        assertEquals(
            AddDeclarationRecoveryPreparationFailure.PREIMAGE_MISMATCH,
            (mismatched as Refinement.Rejected).failure,
        )
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `absent file recovery accepts only the canonical absence marker`() {
        val exact = request()
        val accepted =
            AddDeclarationRecoveryPreparation.admit(
                    exact.planId,
                    exact.source,
                    PlannedSourcePrecondition.Absent,
                    RecoveryPreimage.fromBoundary(ByteArray(0)),
                )
                .refined()
        val rejected =
            AddDeclarationRecoveryPreparation.admit(
                exact.planId,
                exact.source,
                PlannedSourcePrecondition.Absent,
                RecoveryPreimage.fromBoundary("present".toByteArray()),
            ) as Refinement.Rejected

        assertEquals(PlannedSourcePrecondition.Absent, accepted.precondition)
        assertEquals(
            AddDeclarationRecoveryPreparationFailure.ABSENCE_MARKER_MISMATCH,
            rejected.failure,
        )
    }

    @Test
    fun `recovery resolves only to prior state rolled back or recovery required`() {
        val priorStore = InMemoryMutationRecoveryEvidenceStore()
        val priorService = AddDeclarationRecoveryService(priorStore)
        val prepared = priorService.prepare(request()).prepared()
        assertInstanceOf(
            AddDeclarationRecoveryOutcome.RecoveryRequired::class.java,
            priorService.recover(prepared.record.binding) { error("rollback must not run") },
        )

        val rollbackStore = InMemoryMutationRecoveryEvidenceStore()
        val rollbackService = AddDeclarationRecoveryService(rollbackStore)
        val applied = rollbackService.recordApplied(rollbackService.prepare(request()).prepared()).recorded()
        val rolledBack =
            rollbackService.recover(applied.record.binding) {
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
        val unresolved = requiredService.recordApplied(requiredService.prepare(request()).prepared()).recorded()
        val required =
            requiredService.recover(unresolved.record.binding) {
                AddDeclarationRollbackResult.Rejected(AddDeclarationRollbackFailure.CONTENT_DIVERGED)
            }
        assertInstanceOf(AddDeclarationRecoveryOutcome.RecoveryRequired::class.java, required)
        assertInstanceOf(
            MutationRecoveryRecord.RecoveryRequired::class.java,
            requiredStore.current(),
        )
    }

    @Test
    fun `missing durable evidence cannot prove no source effect`() {
        val service = AddDeclarationRecoveryService(InMemoryMutationRecoveryEvidenceStore())
        val outcome = service.recover(request().binding) { error("missing evidence cannot authorize rollback") }
        assertInstanceOf(AddDeclarationRecoveryOutcome.RecoveryRequired::class.java, outcome)
    }

    @Test
    fun `corrupt evidence cannot be mistaken for success`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val service = AddDeclarationRecoveryService(store)
        val binding = request().binding
        store.loadFailure = MutationRecoveryEvidenceFailure.CORRUPT_RECORD

        val outcome = service.recover(binding) { AddDeclarationRollbackResult.RolledBack }

        val required =
            assertInstanceOf(
                AddDeclarationRecoveryOutcome.RecoveryRequired::class.java,
                outcome,
            )
        assertInstanceOf(RecoveryRequiredEvidence.Undurable::class.java, required.evidence)
    }

    @Test
    fun `confirmed saved and committed preimage permits prior state without rollback`() {
        for (loaded in listOf(false, true)) {
            val store = InMemoryMutationRecoveryEvidenceStore()
            val service =
                AddDeclarationRecoveryService(
                    store,
                    RecoveryPreWriteObservationPort { record ->
                        val write = record.preparation.plannedWrites.single()
                        val document =
                            if (loaded) RecoveryDocumentObservation.SavedAndCommitted(write.preimage)
                            else RecoveryDocumentObservation.NotLoaded
                        val proof =
                            ConfirmedRecoveryPreimage.admit(
                                    record,
                                    listOf(RecoverySourceObservation(write.source, write.preimage, document)),
                                )
                                .refined()
                        RecoveryPreWriteObservation.Confirmed(proof)
                    },
                )
            val prepared = service.prepare(request()).prepared()
            val outcome = service.recover(prepared.record.binding) { error("confirmed preimage needs no write") }
            val prior = assertInstanceOf(AddDeclarationRecoveryOutcome.PriorState::class.java, outcome)
            val observed = assertInstanceOf(PriorStateEvidence.ObservedPreWrite::class.java, prior.evidence)
            assertEquals(prepared.record.digest, observed.observation.record.digest)
        }
    }

    @Test
    fun `prewrite recovery rejects dirty divergent missing and duplicate observations`() {
        val service = AddDeclarationRecoveryService(InMemoryMutationRecoveryEvidenceStore())
        val record = service.prepare(request()).prepared().record
        val write = record.preparation.plannedWrites.single()
        val changed = RecoveryPreimage.fromBoundary("after in-memory mutation".toByteArray())
        val exact = RecoverySourceObservation(write.source, write.preimage, RecoveryDocumentObservation.NotLoaded)
        val cases =
            listOf(
                emptyList<RecoverySourceObservation>() to RecoveryPreWriteObservationFailure.WRITE_SET_MISMATCH,
                listOf(exact, exact) to RecoveryPreWriteObservationFailure.WRITE_SET_MISMATCH,
                listOf(exact.copy(savedContent = changed)) to RecoveryPreWriteObservationFailure.SAVED_CONTENT_DIVERGED,
                listOf(exact.copy(document = RecoveryDocumentObservation.SavedAndCommitted(changed))) to
                    RecoveryPreWriteObservationFailure.DOCUMENT_CONTENT_DIVERGED,
                listOf(exact.copy(document = RecoveryDocumentObservation.DirtyOrUncommitted)) to
                    RecoveryPreWriteObservationFailure.DOCUMENT_NOT_READY,
                listOf(exact.copy(document = RecoveryDocumentObservation.Unavailable)) to
                    RecoveryPreWriteObservationFailure.DOCUMENT_NOT_READY,
            )
        for ((sources, failure) in cases) {
            assertEquals(Refinement.Rejected(failure), ConfirmedRecoveryPreimage.admit(record, sources))
        }
    }

    @Test
    fun `legacy empty preimage cannot distinguish absence from an empty existing file`() {
        val original = request()
        val empty = RecoveryPreimage.fromBoundary(ByteArray(0))
        val preparation =
            AddDeclarationRecoveryPreparation.admit(
                    planId = original.planId,
                    source = original.source,
                    precondition = PlannedSourcePrecondition.Absent,
                    preimage = empty,
                )
                .refined()
        val service = AddDeclarationRecoveryService(InMemoryMutationRecoveryEvidenceStore())
        val record = service.prepare(preparation).prepared().record
        assertEquals(
            Refinement.Rejected(RecoveryPreWriteObservationFailure.AMBIGUOUS_PREIMAGE),
            ConfirmedRecoveryPreimage.admit(
                record,
                listOf(RecoverySourceObservation(original.source, empty, RecoveryDocumentObservation.NotLoaded)),
            ),
        )
    }

    @Test
    fun `observation of another recovery record cannot confirm this plan`() {
        val store = InMemoryMutationRecoveryEvidenceStore()
        val otherRequest =
            AddDeclarationRecoveryPreparation.admit(
                    planId = AddDeclarationPlanId.parse("b".repeat(64)).refined(),
                    source = request().source,
                    precondition = request().precondition,
                    preimage = request().preimage,
                )
                .refined()
        val other = AddDeclarationRecoveryService(store).prepare(otherRequest).prepared().record
        val write = other.preparation.plannedWrites.single()
        val proof =
            ConfirmedRecoveryPreimage.admit(
                    other,
                    listOf(
                        RecoverySourceObservation(write.source, write.preimage, RecoveryDocumentObservation.NotLoaded)
                    ),
                )
                .refined()
        val service =
            AddDeclarationRecoveryService(
                store,
                RecoveryPreWriteObservationPort {
                    RecoveryPreWriteObservation.Confirmed(proof)
                },
            )
        val record = service.prepare(request()).prepared().record
        assertInstanceOf(
            AddDeclarationRecoveryOutcome.RecoveryRequired::class.java,
            service.recover(record.binding) { error("wrong record must not write") },
        )
    }

    private fun request(): AddDeclarationRecoveryPreparation {
        val content = "before"
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return AddDeclarationRecoveryPreparation.admit(
                planId = AddDeclarationPlanId.parse("a".repeat(64)).refined(),
                source = RecoverySourcePath.parse("/workspace/app/src/main/kotlin/sample/Service.kt").refined(),
                precondition =
                    PlannedSourcePrecondition.Existing(WorkspaceSourceContentHash.parse(sha256(bytes)).refined()),
                preimage = RecoveryPreimage.fromBoundary(bytes),
            )
            .refined()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}

private class InMemoryMutationRecoveryEvidenceStore : MutationRecoveryEvidenceStore {
    val records = linkedMapOf<String, MutationRecoveryRecord>()
    var loadFailure: MutationRecoveryEvidenceFailure? = null

    override fun prepare(
        record: MutationRecoveryRecord.PreWriteDurable
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.PreWriteDurable> = persist(record)

    override fun recordApplied(
        prior: MutationRecoveryRecord.PreWriteDurable,
        record: MutationRecoveryRecord.AppliedWritesDurable,
    ): MutationRecoveryPersistResult<MutationRecoveryRecord.AppliedWritesDurable> = transition(prior, record)

    override fun <Record : MutationRecoveryRecord.Terminal> recordTerminal(
        prior: MutationRecoveryRecord.AppliedWritesDurable,
        record: Record,
    ): MutationRecoveryPersistResult<Record> = transition(prior, record)

    override fun load(
        binding: io.github.amichne.kast.evidence.contract.MutationPlanBinding
    ): MutationRecoveryLoadResult {
        loadFailure?.let {
            return MutationRecoveryLoadResult.Rejected(it)
        }
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
    ): MutationRecoveryPersistResult<T> =
        if (records[prior.binding.value]?.digest == prior.digest) {
            persist(record)
        } else {
            MutationRecoveryPersistResult.Rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
        }
}

private fun PrepareAddDeclarationRecoveryResult.prepared(): PreparedAddDeclarationRecovery =
    (this as PrepareAddDeclarationRecoveryResult.Prepared).recovery

private fun RecordAppliedAddDeclarationResult.recorded(): AppliedAddDeclarationRecovery =
    (this as RecordAppliedAddDeclarationResult.Recorded).recovery

private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
