package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryImpactPeerProofFailure
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The real completed-read rule with one scripted child boundary; no installed/compiler observation is claimed. */
class HostedPeerSiteAcquisitionTest {
    @Test
    fun `post read freshness rejection cannot publish a provisional native selection`() = runTest {
        val f = HostedPeerSiteFixture()
        val observation = Observation()
        val accounting = ReadAcquisitionAccounting()
        var calls = 0
        val result =
            acquireHostedPeerSites(
                listOf(f.selected),
                budget(f),
                accounting,
                read =
                    HostedPeerSiteReadPort { selection, grant ->
                        assertEquals(0, calls++)
                        assertSame(f.selected.site.enclosing, selection.site.enclosing)
                        f.nativeSelection(
                            grant
                        ) // The callback proof exists, but its owning transaction rejected afterwards.
                        HostedSemanticReadResult.Rejected(
                            HostedQueryFailure.CONTENT_MOVED,
                            HostedQueryStage.CONTENT_REVALIDATION,
                        )
                    },
                observation = observation,
                clock = { 0 },
            )
        val rejected = assertInstanceOf(HostedPeerSiteAdmissions.HostedRejected::class.java, result)
        assertSame(HostedQueryFailure.CONTENT_MOVED, rejected.response.failure)
        assertEquals(HostedQueryStage.CONTENT_REVALIDATION, rejected.response.stage)
        assertEquals(0, accounting.snapshot().value)
        assertEquals(1, calls)
        observation.assertCounts(started = 1, completed = 0, rejected = 1)
    }

    @Test
    fun `completed child retains exact target and debits aggregate work and whole elapsed once`() = runTest {
        val f = HostedPeerSiteFixture()
        val accounting = ReadAcquisitionAccounting()
        val observation = Observation()
        val times = ArrayDeque(listOf(0L, 8_000_000L))
        var calls = 0
        val result =
            acquireHostedPeerSites(
                listOf(f.selected),
                budget(f),
                accounting,
                read =
                    HostedPeerSiteReadPort { _, grant ->
                        assertEquals(0, calls++)
                        HostedSemanticReadResult.Completed(
                            HostedPeerSiteEvaluation.Selected(
                                f.nativeSelection(grant),
                                f.authority,
                                grant,
                                RelationWorkCount.parse(5).peerValue(),
                            )
                        )
                    },
                observation = observation,
                clock = { times.removeFirst() },
            )
        val admitted = assertInstanceOf(HostedPeerSiteAdmissions.Admitted::class.java, result).values.single()
        assertSame(f.authority, admitted.acquisition.completedAuthority)
        assertEquals(1, admitted.selection.examinedWorkUnits.value)
        assertEquals(5, admitted.acquisition.examinedWorkUnits.value)
        assertEquals(8_000_000, admitted.acquisition.elapsed.value)
        assertEquals(5, accounting.snapshot().value)
        val remaining = accounting.remaining(budget(f).resources).peerValue()
        assertEquals(95, remaining.workUnitLimit.value)
        assertEquals(992, remaining.elapsedTimeLimit.value)
        assertEquals(0, times.size)
        observation.assertCounts(started = 1, completed = 1, rejected = 0)
    }

    @Test
    fun `oversized child work receipt remains a finite rejected proof`() = runTest {
        val f = HostedPeerSiteFixture()
        val observation = Observation()
        val accounting = ReadAcquisitionAccounting()
        val result =
            acquireHostedPeerSites(
                listOf(f.selected),
                budget(f),
                accounting,
                read =
                    HostedPeerSiteReadPort { _, grant ->
                        HostedSemanticReadResult.Completed(
                            HostedPeerSiteEvaluation.Selected(
                                f.nativeSelection(grant),
                                f.authority,
                                grant,
                                RelationWorkCount.parse(101).peerValue(),
                            )
                        )
                    },
                observation = observation,
                clock = { 0 },
            )
        assertEquals(
            QueryImpactPeerProofFailure.WORK_RECEIPT_EXCEEDS_GRANT,
            assertInstanceOf(HostedPeerSiteAdmissions.ProofRejected::class.java, result).cause,
        )
        assertEquals(0, accounting.snapshot().value)
        observation.assertCounts(started = 1, completed = 0, rejected = 1)
    }

    @Test
    fun `exhausted original source allowance never starts a peer effect`() = runTest {
        val f = HostedPeerSiteFixture()
        val observation = Observation()
        val accounting = ReadAcquisitionAccounting().apply { record(100, 0) }
        val result =
            acquireHostedPeerSites(
                listOf(f.selected),
                budget(f),
                accounting,
                read = HostedPeerSiteReadPort { _, _ -> error("Exhausted grant cannot invoke a child") },
                observation = observation,
                clock = { error("No child clock may be consumed") },
            )
        val failure = assertInstanceOf(HostedPeerSiteAdmissions.QueryRejected::class.java, result).cause
        val admission =
            (failure as QueryRunRejection.ImpactSourceRejected).cause as QueryImpactSourceFailureDocument.Admission
        assertEquals(QueryImpactSourceFailureCode.WORK_LIMIT_REACHED, admission.cause)
        observation.assertCounts(started = 0, completed = 0, rejected = 0)
    }

    @Test
    fun `empty peer selection never acquires or charges a child`() = runTest {
        val f = HostedPeerSiteFixture()
        val accounting = ReadAcquisitionAccounting()
        val result =
            acquireHostedPeerSites(
                emptyList(),
                budget(f),
                accounting,
                read = HostedPeerSiteReadPort { _, _ -> error("No peer was selected") },
                clock = { error("No child clock may be consumed") },
            )
        assertEquals(emptyList<Any>(), assertInstanceOf(HostedPeerSiteAdmissions.Admitted::class.java, result).values)
        assertEquals(0, accounting.snapshot().value)
    }

    @Test
    fun `child cannot publish a larger grant than the original source offered`() = runTest {
        assertRejectedGrant(GrantDimension.WORK)
    }

    @Test fun `child cannot enlarge offered result capacity`() = runTest { assertRejectedGrant(GrantDimension.RESULTS) }

    @Test fun `child cannot enlarge offered elapsed capacity`() = runTest { assertRejectedGrant(GrantDimension.TIME) }

    @Test
    fun `child cannot enlarge offered native storage capacity`() = runTest {
        assertRejectedGrant(GrantDimension.STORAGE)
    }

    private suspend fun assertRejectedGrant(dimension: GrantDimension) {
        val f = HostedPeerSiteFixture()
        val accounting = ReadAcquisitionAccounting()
        val observation = Observation()
        val result =
            acquireHostedPeerSites(
                listOf(f.selected),
                budget(f),
                accounting,
                read =
                    HostedPeerSiteReadPort { _, offered ->
                        assertEquals(100, offered.resources.workUnitLimit.value)
                        val larger = dimension.inflate(offered)
                        HostedSemanticReadResult.Completed(
                            HostedPeerSiteEvaluation.Selected(
                                f.nativeSelection(larger),
                                f.authority,
                                larger,
                                RelationWorkCount.parse(3).peerValue(),
                            )
                        )
                    },
                observation = observation,
                clock = { 0 },
            )
        val rejected = assertInstanceOf(HostedPeerSiteAdmissions.HostedRejected::class.java, result)
        assertSame(HostedQueryFailure.BUDGET_EXCEEDED, rejected.response.failure)
        assertEquals(HostedQueryStage.RESULT_DETACHED, rejected.response.stage)
        assertEquals(0, accounting.snapshot().value)
        observation.assertCounts(started = 1, completed = 0, rejected = 1)
    }

    enum class GrantDimension {
        WORK,
        RESULTS,
        TIME,
        STORAGE;

        fun inflate(offered: RelationBudget): RelationBudget =
            when (this) {
                WORK ->
                    offered.copy(
                        resources =
                            offered.resources.copy(
                                workUnitLimit =
                                    WorkUnitLimit.parse(offered.resources.workUnitLimit.value + 1).peerValue()
                            )
                    )
                RESULTS ->
                    offered.copy(
                        resources =
                            offered.resources.copy(
                                resultLimit = ResultLimit.parse(offered.resources.resultLimit.value + 1).peerValue()
                            )
                    )
                TIME ->
                    offered.copy(
                        resources =
                            offered.resources.copy(
                                elapsedTimeLimit =
                                    ElapsedTimeLimitMillis.parse(offered.resources.elapsedTimeLimit.value + 1)
                                        .peerValue()
                            )
                    )
                STORAGE ->
                    offered.copy(returnedBytes = RelationByteLimit.parse(offered.returnedBytes.value + 1).peerValue())
            }
    }

    @Test
    fun `multiple clamped children share original work and whole wall time without resetting allowance`() = runTest {
        val f = HostedPeerSiteFixture()
        val accounting = ReadAcquisitionAccounting()
        val offeredWork = mutableListOf<Long>()
        val offeredTime = mutableListOf<Long>()
        val times = ArrayDeque(listOf(0L, 8_000_000L, 8_000_000L, 11_000_000L))
        val result =
            acquireHostedPeerSites(
                f.twoSelections(),
                budget(f),
                accounting,
                read =
                    HostedPeerSiteReadPort { selection, offered ->
                        offeredWork += offered.resources.workUnitLimit.value
                        offeredTime += offered.resources.elapsedTimeLimit.value
                        val index = offeredWork.size - 1
                        assertEquals(index * 2, selection.site.range.start.value)
                        val clamped =
                            offered.copy(
                                resources =
                                    offered.resources.copy(
                                        workUnitLimit = WorkUnitLimit.parse(80).peerValue(),
                                        elapsedTimeLimit = ElapsedTimeLimitMillis.parse(300).peerValue(),
                                    )
                            )
                        HostedSemanticReadResult.Completed(
                            HostedPeerSiteEvaluation.Selected(
                                f.nativeSelection(clamped, index * 2),
                                f.authority,
                                clamped,
                                RelationWorkCount.parse(if (index == 0) 5 else 3).peerValue(),
                            )
                        )
                    },
                clock = { times.removeFirst() },
            )
        val admissions = assertInstanceOf(HostedPeerSiteAdmissions.Admitted::class.java, result).values
        assertEquals(2, admissions.size)
        assertEquals(listOf(100L, 95L), offeredWork)
        assertEquals(listOf(1000L, 992L), offeredTime)
        assertEquals(listOf(80L, 80L), admissions.map { it.acquisition.grant.resources.workUnitLimit.value })
        assertEquals(8, accounting.snapshot().value)
        assertEquals(92, accounting.remaining(budget(f).resources).peerValue().workUnitLimit.value)
        assertEquals(989, accounting.remaining(budget(f).resources).peerValue().elapsedTimeLimit.value)
        assertEquals(0, times.size)
    }

    private fun budget(f: HostedPeerSiteFixture) =
        QueryBudget(
            f.peer.budget.resources,
            QueryByteLimit.parse(100_000).peerValue(),
            QueryByteLimit.parse(8_388_608).peerValue(),
        )

    private class Observation : IntellijReadObservation {
        private val counts = mutableMapOf<IntellijReadCounter, Int>()
        private val phases = mutableListOf<IntellijReadPhase>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = counts.getOrDefault(counter, 0) + amount
        }

        override fun phase(value: IntellijReadPhase) {
            phases += value
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

        fun assertCounts(started: Int, completed: Int, rejected: Int) {
            assertEquals(started, counts.getOrDefault(IntellijReadCounter.PEER_SITE_READS_STARTED, 0))
            assertEquals(completed, counts.getOrDefault(IntellijReadCounter.PEER_SITE_READS_COMPLETED, 0))
            assertEquals(rejected, counts.getOrDefault(IntellijReadCounter.PEER_SITE_READS_REJECTED, 0))
            assertEquals(List(started * 2) { IntellijReadPhase.PEER_SITE_ADMISSION }, phases)
        }
    }
}
