package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AppliedSourceWrite
import io.github.amichne.kast.change.apply.AppliedUnverified
import io.github.amichne.kast.change.apply.ChangeApplyRequest
import io.github.amichne.kast.change.apply.MutationDurabilityResult
import io.github.amichne.kast.change.apply.ObservedAbsentMutationSource
import io.github.amichne.kast.change.apply.RequestedMutationWriteScope
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.apply.SourceWriteResult
import io.github.amichne.kast.change.contract.AddFilePlanResult
import io.github.amichne.kast.change.contract.ChangePlanIssuance
import io.github.amichne.kast.change.verify.ChangeVerificationObservation
import io.github.amichne.kast.change.verify.ChangeVerificationObservationRejection
import io.github.amichne.kast.change.verify.VerifiedMutationRequest
import io.github.amichne.kast.change.verify.VerifiedMutationResult
import io.github.amichne.kast.evidence.contract.MutationPlanBinding
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceFailure
import io.github.amichne.kast.evidence.contract.MutationRecoveryEvidenceStore
import io.github.amichne.kast.evidence.contract.MutationRecoveryLoadResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryPersistResult
import io.github.amichne.kast.evidence.contract.MutationRecoveryRecord
import io.github.amichne.kast.evidence.contract.OpenCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeApplyHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeAuthority
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateCapture
import io.github.amichne.kast.workspace.contract.WorkspaceCandidateReconciliation
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefresh
import io.github.amichne.kast.workspace.contract.WorkspaceIndexRefreshFailure
import io.github.amichne.kast.workspace.contract.WorkspaceReconciliationPort
import io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceObservation
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.service.WorkspacePublicationCoordinator
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SerializedMutationTransitionTest {
    @Test
    fun `application does not race its own successor publication`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        assertEquals(0, fixture.refreshes)
        assertEquals(13L, (fixture.coordinator.inspect() as WorkspaceRuntimeState.Ready).workspace.generation.value)
        val result = fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))

        assertTrue(result is VerifiedMutationResult.RejectedAfterResultingWorkspace, result.toString())
        assertEquals(listOf(14L), fixture.observedGenerations)
        assertEquals(1, fixture.refreshes)
    }

    @Test
    fun `resulting publication refreshes physical state before observing compiler evidence`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))

        assertEquals(1, fixture.refreshes)
        assertEquals(listOf(14L), fixture.observedGenerations)
    }

    @Test
    fun `stale prior cannot refresh or adopt an unrelated successor`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        fixture.graph.operations.indexSync.synchronize()
        assertEquals(1, fixture.refreshes)

        val result = fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))

        assertTrue(result is VerifiedMutationResult.RejectedBeforePublication)
        assertEquals(1, fixture.refreshes)
        assertEquals(emptyList<Long>(), fixture.observedGenerations)
    }

    @Test
    fun `refresh source invalidation retains only the admitted prior authority`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        fixture.onRefresh = {
            fixture.coordinator.observe(WorkspaceSignal.Source)
            WorkspaceIndexRefresh.Refreshed
        }

        fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))

        assertEquals(listOf(14L), fixture.observedGenerations)
        assertEquals(1, fixture.refreshes)
    }

    @Test
    fun `failed physical refresh cannot publish resulting generation`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        fixture.onRefresh = { WorkspaceIndexRefresh.Rejected(WorkspaceIndexRefreshFailure.INDEXING_FAILED) }

        val result = fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))

        assertTrue(result is VerifiedMutationResult.RejectedBeforePublication)
        assertEquals(emptyList<Long>(), fixture.observedGenerations)
        assertEquals(13L, (fixture.coordinator.inspect() as WorkspaceRuntimeState.Ready).workspace.generation.value)
    }

    @Test
    fun `refresh cancellation preserves interruption and releases shared transition`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val applied = fixture.apply()
        val cancelled = object : java.util.concurrent.CancellationException("refresh interrupted") {}
        fixture.onRefresh = { throw cancelled }

        assertSame(
            cancelled,
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                fixture.graph.operations.changeApply.verify.verify(VerifiedMutationRequest(fixture.plan, applied))
            },
        )
        assertEquals(emptyList<Long>(), fixture.observedGenerations)
        fixture.onRefresh = { WorkspaceIndexRefresh.Refreshed }
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { fixture.graph.operations.indexSync.synchronize() }.get(5, TimeUnit.SECONDS)
            assertEquals(14L, (fixture.coordinator.inspect() as WorkspaceRuntimeState.Ready).workspace.generation.value)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `canonical application excludes competing readiness through compiler observation`(@TempDir root: Path) {
        val fixture = MutationTransitionFixture(root)
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val competitor = AtomicReference<java.util.concurrent.Future<*>>()
        fixture.afterWrite = {
            competitor.set(
                pool.submit {
                    entered.countDown()
                    try {
                        fixture.graph.operations.indexSync.synchronize()
                    } finally {
                        completed.countDown()
                    }
                }
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS), "readiness entered during application")
        }
        fixture.beforeObservation = {
            assertEquals(1L, completed.count, "readiness entered before verification finished")
            // Compiler callbacks inspect raw coordinator state from another thread. They must
            // remain usable while the request thread owns the workspace transition.
            val observed = AtomicReference<WorkspaceRuntimeState>()
            val callback = Thread { observed.set(fixture.coordinator.inspect()) }
            callback.start()
            callback.join(5_000)
            assertFalse(callback.isAlive, "compiler observation deadlocked on transition owner")
            assertTrue(observed.get() is WorkspaceRuntimeState.Ready)
        }
        val authority = CanonicalChangeAuthority()
        val identity = (authority.issuePlan(fixture.plan) as ChangePlanIssuance.Issued).identity
        val handler =
            CanonicalChangeApplyHandler(
                fixture.graph.semanticWorkspace,
                fixture.graph.operations.changeApply,
                authority,
            )
        try {
            runBlocking {
                handler.execute(
                    io.github.amichne.kast.protocol.contract.ChangeApplyRequest(
                        ProtocolText.parse(identity.value).mutationRefined()
                    )
                )
            }
            competitor.get().get(5, TimeUnit.SECONDS)
            assertEquals(listOf(14L), fixture.observedGenerations)
            assertEquals(0L, completed.count)
        } finally {
            pool.shutdownNow()
        }
    }
}

private class MutationTransitionFixture(root: Path) {
    private val admitted = InstalledChangeProtocolFixture.create(root.toRealPath())
    private var state = admitted.published.sourceState
    private var physicalState = state
    var refreshes = 0
    var onRefresh: () -> WorkspaceIndexRefresh = { WorkspaceIndexRefresh.Refreshed }
    var afterWrite: () -> Unit = {}
    var beforeObservation: () -> Unit = {}
    val observedGenerations = mutableListOf<Long>()
    val coordinator =
        WorkspacePublicationCoordinator(
                object : WorkspaceReconciliationPort {
                    override fun capture(signals: Set<WorkspaceSignal>): WorkspaceCandidateCapture =
                        WorkspaceCandidateCapture.Captured(WorkspaceCandidate(admitted.published.root, state))

                    override fun reconcile(candidate: WorkspaceCandidate): WorkspaceCandidateReconciliation =
                        WorkspaceCandidateReconciliation.Reconciled(
                            ReconciledWorkspace.admit(
                                    candidate,
                                    WorkspaceEvidenceKind.entries.toSet(),
                                    admitted.published.sourceRoots,
                                )
                                .mutationRefined()
                        )
                },
                MutationPublicationTransaction(),
            )
            .also { it.reconcile() }
    val graph =
        KastRuntimeComposition.constructGraph(
            coordinator,
            KastRuntimeCompositionTest.semanticPorts(),
            KastRuntimeCompositionTest.topologyPorts(),
            IndexRuntimePorts(
                refresh = {
                    refreshes++
                    onRefresh().also { result ->
                        if (result == WorkspaceIndexRefresh.Refreshed) state = physicalState
                    }
                },
                sourceObservation = { WorkspaceSourceObservation.Observed(physicalState) },
            ),
            KastRuntimeCompositionTest.changePorts()
                .copy(
                    recoveryEvidence = MutationRecoveryStore(),
                    sourceObserver = { source ->
                        SourceObservationResult.Observed(
                            ObservedAbsentMutationSource.fromPhysicalBoundary(source, SourceWriteAccess.Writable)
                        )
                    },
                    sourceWriter = { authority, durability ->
                        val applied =
                            AppliedSourceWrite.observe(
                                    authority,
                                    authority.postimageBytesAtIntellijBoundary(),
                                    setOf(authority.source.path.value),
                                )
                                .mutationRefined()
                        when (durability.recordApplied()) {
                            MutationDurabilityResult.Durable -> {
                                physicalState = WorkspaceStateIdentity.parse("after-mutation").mutationRefined()
                                afterWrite()
                                SourceWriteResult.Applied(applied)
                            }
                            is MutationDurabilityResult.Rejected ->
                                SourceWriteResult.RejectedAfterRollback(SourceWriteFailure.DURABILITY_REJECTED)
                        }
                    },
                    verificationObserver = { request ->
                        beforeObservation()
                        observedGenerations.add(request.resulting.workspace.generation.value)
                        ChangeVerificationObservation.Rejected(
                            ChangeVerificationObservationRejection.COMPILER_OBSERVATION_REJECTED
                        )
                    },
                ),
            KastObservability.Disabled,
        )
    val plan = (graph.operations.changePlan.addFile.plan(admitted.addFile) as AddFilePlanResult.Planned).plan

    fun apply(): AppliedUnverified =
        graph.operations.changeApply.apply.apply(
            ChangeApplyRequest(
                plan,
                admitted.published,
                RequestedMutationWriteScope(
                    admitted.published.root,
                    plan.writes.entries.mapTo(linkedSetOf()) { it.source },
                ),
            )
        ) as AppliedUnverified
}

private data object MutationOpenPublication : OpenCanonicalWorkspacePublication

private data class MutationPreparedPublication(val candidate: ReconciledWorkspace) :
    PreparedCanonicalWorkspacePublication

private class MutationPublicationTransaction : WorkspacePublicationTransaction {
    private var generation = 12L

    override fun begin(): WorkspacePublicationOpening = WorkspacePublicationOpening.Opened(MutationOpenPublication)

    override fun prepare(
        open: OpenCanonicalWorkspacePublication,
        candidate: ReconciledWorkspace,
    ): WorkspacePublicationPreparation =
        WorkspacePublicationPreparation.Prepared(MutationPreparedPublication(candidate))

    override fun commit(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationResult =
        WorkspacePublicationResult.Advanced(
            PublishedWorkspace.publish(
                (prepared as MutationPreparedPublication).candidate,
                EvidenceGeneration.parse(++generation).mutationRefined(),
            )
        )

    override fun discard(open: OpenCanonicalWorkspacePublication): WorkspacePublicationDiscard =
        WorkspacePublicationDiscard.Discarded

    override fun discard(prepared: PreparedCanonicalWorkspacePublication): WorkspacePublicationDiscard =
        WorkspacePublicationDiscard.Discarded
}

private class MutationRecoveryStore : MutationRecoveryEvidenceStore {
    private val records = mutableMapOf<MutationPlanBinding, MutationRecoveryRecord>()

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

    override fun load(binding: MutationPlanBinding): MutationRecoveryLoadResult =
        records[binding]?.let(MutationRecoveryLoadResult::Found) ?: MutationRecoveryLoadResult.Absent(binding)

    private fun <Record : MutationRecoveryRecord> persist(record: Record): MutationRecoveryPersistResult<Record> {
        records[record.binding] = record
        return MutationRecoveryPersistResult.Durable(record)
    }

    private fun <Record : MutationRecoveryRecord> transition(
        prior: MutationRecoveryRecord,
        record: Record,
    ): MutationRecoveryPersistResult<Record> =
        if (records[prior.binding]?.digest == prior.digest) persist(record)
        else MutationRecoveryPersistResult.Rejected(MutationRecoveryEvidenceFailure.PRIOR_STATE_MISMATCH)
}

private fun <Value, Failure> Refinement<Value, Failure>.mutationRefined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
