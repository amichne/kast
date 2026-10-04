package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionRequestFailure
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.relation.contract.LocalBindingReferenceScan
import io.github.amichne.kast.relation.contract.LocalReferenceKey
import io.github.amichne.kast.relation.contract.LocalReferenceKind
import io.github.amichne.kast.relation.contract.LocalReferenceResolution
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowCompilerPort
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryLocalBindingAdverseTest {
    @Test
    fun `timeout while replaying the consumed prefix retains a resumable remainder`() = runTest {
        val s = ScanFixture()
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        val predecessor = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val remainder = predecessor.impact!!.remainders.getValue(s.binding)
        var visits = 0
        s.elapsedMillis = { if (++visits >= 2) 10000L else 0L }
        val timed = s.service.run(s.f.request(checkpoint = predecessor)) as QueryExecutionResult.Qualified
        assertTrue(timed.continuation is QueryContinuationState.Resumable)
        val checkpoint = (timed.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val snapshot = requireNotNull(checkpoint.impact)
        assertEquals(remainder.consumed, snapshot.remainders.getValue(s.binding).consumed)
        assertEquals(remainder.emitted, snapshot.remainders.getValue(s.binding).emitted)
        assertTrue(snapshot.readRejections.isEmpty())
        assertEquals(listOf(0, 1), s.confirmed)
        assertEquals(listOf(4L, 2L), snapshot.receipts.getValue(s.binding).map { it.examinedWorkUnits.value })
        s.elapsedMillis = { 0L }
        val drained = s.service.run(s.f.request(checkpoint = checkpoint)) as QueryExecutionResult.Qualified
        assertTrue(drained.continuation is QueryContinuationState.Terminal)
        assertEquals(s.destination, (drained.result.rows as QueryRows.ValuePaths).values.single().destination)
        assertEquals(listOf(0, 1, 2, 3, 4), s.confirmed)
        s.native.assertConsumed()
    }

    @Test
    fun `elapsed suspension resumes the consumed prefix without confirming it again`() = runTest {
        val s = ScanFixture()
        s.elapsedMillis = { if (s.confirmed.size < 2) 0L else 10000L }
        val first = s.service.run(s.f.request()) as QueryExecutionResult.Qualified
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        assertEquals(listOf(0, 1), s.confirmed)
        assertEquals(2, checkpoint.impact!!.remainders.getValue(s.binding).consumed.size)
        s.elapsedMillis = { 0L }
        val last = s.service.run(s.f.request(checkpoint = checkpoint)) as QueryExecutionResult.Qualified
        assertTrue(last.continuation is QueryContinuationState.Terminal)
        assertEquals(listOf(0, 1, 2, 3, 4), s.confirmed)
        assertEquals(s.destination, (last.result.rows as QueryRows.ValuePaths).values.single().destination)
        s.native.assertConsumed()
    }

    @Test
    fun `repeated time suspensions before any candidate yield finitely and drain later`() = runTest {
        val s = ScanFixture()
        s.elapsedMillis = { 10000L }
        var checkpoint: QueryCheckpoint? = null
        repeat(3) {
            val timed = s.service.run(s.f.request(checkpoint = checkpoint)) as QueryExecutionResult.Qualified
            assertTrue(timed.continuation is QueryContinuationState.Resumable)
            checkpoint = (timed.continuation as QueryContinuationState.Resumable).checkpoint
            val snapshot = (checkpoint as PipelineCheckpoint).impact!!
            assertTrue(snapshot.remainders.getValue(s.binding).consumed.isEmpty())
            assertTrue(snapshot.readRejections.isEmpty())
            assertEquals(it + 1, snapshot.receipts.getValue(s.binding).size)
            assertTrue(s.confirmed.isEmpty())
        }
        s.elapsedMillis = { 0L }
        val drained = s.service.run(s.f.request(checkpoint = checkpoint)) as QueryExecutionResult.Qualified
        assertTrue(drained.continuation is QueryContinuationState.Terminal)
        assertEquals(s.destination, (drained.result.rows as QueryRows.ValuePaths).values.single().destination)
        assertEquals(listOf(0, 1, 2, 3, 4), s.confirmed)
        s.native.assertConsumed()
    }

    @Test
    fun `a byte grant too small for any candidate rejects without enumeration`() = runTest {
        val s = ScanFixture()
        val grant = s.f.request().budget
        val request =
            ValueFlowRequest(
                s.binding,
                RelationBudget(grant.resources, RelationByteLimit.parse(1).value()),
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        val rejected = s.port.read(request) as ValueFlowRead.Rejected
        assertEquals(ValueFlowRejection.GRANT_TOO_SMALL, rejected.cause)
        assertEquals(1L, rejected.examinedWorkUnits.value)
        assertTrue(s.confirmed.isEmpty())
    }

    @Test
    fun `authority rejection after suspension preserves confirmed prefix and a finite rejection`() = runTest {
        val s = ScanFixture()
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val before = checkpoint.impact!!
        assertEquals(1, (before.reads[s.binding] as ValueFlowRead.Observed).step.transfers.size)
        assertEquals(setOf(s.binding), before.remainders.keys)
        val authorityMoved = ValueFlowCompilerPort { request ->
            if (request.source == s.binding)
                ValueFlowRead.Rejected(ValueFlowRejection.AUTHORITY_MOVED, RelationWorkCount.parse(1).value())
            else s.port.read(request)
        }
        val result =
            s.f.service(authorityMoved).run(s.f.request(work = 20, checkpoint = checkpoint))
                as QueryExecutionResult.Qualified
        assertTrue(result.continuation is QueryContinuationState.Terminal)
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(setOf(s.destination, s.binding), rows.values.map { it.destination }.toSet())
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(
            ValueFlowRejection.AUTHORITY_MOVED,
            (ledger.readRejections.single() as QueryImpactReadRejection.Native).cause,
        )
        assertEquals(1L, ledger.readRejections.single().examinedWorkUnits.value)
        assertEquals(
            listOf(4L, 20L),
            ledger.readReceipts.getValue(s.binding).map { it.domain.budget.resources.workUnitLimit.value },
        )
        assertEquals(listOf(4L, 1L), ledger.readReceipts.getValue(s.binding).map { it.examinedWorkUnits.value })
        assertEquals(1, ledger.observations.single { it.source == s.binding }.transfers.size)
        assertEquals(listOf(0, 1), s.confirmed)
        assertSame(before, checkpoint.impact)
        s.native.assertConsumed()
    }

    @Test
    fun `too little work for progress rejects finitely while retaining the prior observation`() = runTest {
        val s = ScanFixture()
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        var checkpoint: QueryCheckpoint? = (first.continuation as QueryContinuationState.Resumable).checkpoint
        val paths = mutableListOf<QueryImpactPath>()
        var ledger: QueryImpactLedger? = null
        repeat(10) {
            if (checkpoint == null) return@repeat
            val result = s.service.run(s.f.request(work = 2, checkpoint = checkpoint)) as QueryExecutionResult.Qualified
            val rows = result.result.rows as QueryRows.ValuePaths
            paths += rows.values
            ledger = (rows.accounting as? QueryValuePathAccounting.Investigated)?.ledger ?: ledger
            checkpoint = (result.continuation as? QueryContinuationState.Resumable)?.checkpoint
        }
        assertNull(checkpoint)
        assertEquals(2, paths.size)
        assertEquals(
            ValueFlowRejection.GRANT_TOO_SMALL,
            (ledger!!.readRejections.single() as QueryImpactReadRejection.Native).cause,
        )
        assertEquals(listOf(0, 1), s.confirmed)
        assertEquals(1, ledger.observations.single { it.source == s.binding }.transfers.size)
        s.native.assertConsumed()
    }

    @Test
    fun `cancellation through the scan callback leaves the published predecessor immutable`() = runTest {
        val s = ScanFixture(destinationExpected = false)
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val before = checkpoint.impact!!
        s.canceled = true
        var canceled = false
        try {
            s.service.run(s.f.request(checkpoint = checkpoint))
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
        assertSame(before, checkpoint.impact)
        assertEquals(2, before.remainders.getValue(s.binding).consumed.size)
        assertEquals(listOf(0, 1), s.confirmed)
        s.native.assertConsumed()
    }

    @Test
    fun `epoch movement rejects the checkpoint before any additional enumeration`() = runTest {
        val s = ScanFixture(destinationExpected = false)
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint
        val foreign = QueryImpactExecutionFixture(generation = 2)
        assertEquals(
            Refinement.Rejected(QueryExecutionRequestFailure.CHECKPOINT_MISMATCH),
            QueryExecutionRequest.create(s.f.plan, foreign.lease, s.f.request().budget, checkpoint),
        )
        assertEquals(listOf(0, 1), s.confirmed)
        s.native.assertConsumed()
    }

    @Test
    fun `insufficient retention terminates through the existing checkpoint owner`() = runTest {
        val s = ScanFixture(destinationExpected = false)
        val first = s.service.run(s.f.request(work = 5)) as QueryExecutionResult.Qualified
        val checkpoint = (first.continuation as QueryContinuationState.Resumable).checkpoint
        val result =
            s.service.run(s.f.request(checkpoint = checkpoint, checkpointBytes = 1)) as QueryExecutionResult.Qualified
        assertTrue(result.continuation is QueryContinuationState.Terminal)
        assertEquals(listOf(0, 1), s.confirmed)
        val paths = (result.result.rows as QueryRows.ValuePaths).values
        assertTrue(paths.single().terminal is QueryImpactTerminal.Unresolved.ExecutionStop)
        s.native.assertConsumed()
    }

    private class ScanFixture(destinationExpected: Boolean = true) {
        val f = QueryImpactExecutionFixture()
        val binding = f.site(30, ValueRole.LocalBinding)
        val destination = f.site(50, ValueRole.LocalRead)
        val native by lazy {
            f.script(
                listOf(
                    ImpactReadExpectation(
                        f.producer.site,
                        listOf(f.edge(f.producer.site, binding, ValueTransferKind.LOCAL_BINDING)),
                    )
                ) +
                    if (destinationExpected)
                        listOf(
                            ImpactReadExpectation(
                                destination,
                                causes = listOf(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                            )
                        )
                    else emptyList()
            )
        }
        val confirmed = mutableListOf<Int>()
        var canceled = false
        var elapsedMillis: () -> Long = { 0L }
        val port = ValueFlowCompilerPort { request ->
            if (request.source != binding) native.port.read(request)
            else
                LocalBindingReferenceScan<Int>(
                        request,
                        RelationRequest.start(
                            binding.enclosing as RelationEndpoint.Resolved,
                            RelationMeaning.References,
                            request.budget,
                            request.boundary,
                        ),
                        { visit -> for (i in 0..4) if (!visit(i)) break },
                        { i ->
                            val range = ExactDeclarationTextRange.parse(100 + i, 101 + i).value()
                            LocalReferenceKey(
                                range,
                                range,
                                LocalReferenceKind.fromBoundary("fixture.Reference").value(),
                            )
                        },
                        { i ->
                            confirmed += i
                            if (i == 1) LocalReferenceResolution.Transfer(destination)
                            else LocalReferenceResolution.OtherBinding
                        },
                        { if (canceled) throw CancellationException("scripted cancellation") },
                        { elapsedMillis() },
                    )
                    .read()
        }
        val service = f.service(port)
    }
}

private fun <V> Refinement<V, *>.value(): V = (this as Refinement.Refined).value
