package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.QueryWorkUsage
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactWorkAdmissionTest {
    @Test
    fun `cached fanout needs work before child routes exist`() = runTest {
        val f = QueryImpactExecutionFixture()
        val cached = cachedFanout(f)
        val native = f.script(emptyList())
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(f.request(work = 2, checkpoint = cached)),
            )
        assertPendingWithoutProgress(result)
        assertEquals(1L, (result.workUsage as QueryWorkUsage.Observed).count.value)
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `cached branches and terminal paths spend the exact work count`() = runTest {
        val f = QueryImpactExecutionFixture()
        val cached = cachedFanout(f)
        val native = f.script(emptyList())
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                f.service(native.port).run(f.request(work = 7, checkpoint = cached)),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(listOf(30, 40), rows.values.map { it.destination.range.startInclusive })
        assertEquals(QueryValuePathAccountingStatus.Conserved, rows.accountingStatus)
        assertEquals(7L, (result.workUsage as QueryWorkUsage.Observed).count.value)
        assertEquals(emptyList<ValueSite>(), native.examined)
        native.assertConsumed()
    }

    @Test
    fun `cached supported end needs terminal work and cannot claim fresh progress`() = runTest {
        val f = QueryImpactExecutionFixture()
        val native = f.script(emptyList())
        val checkpoint = checkpoint(f, listOf(observe(f, f.producer.site, emptyList())))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                f.service(native.port).run(f.request(work = 1, checkpoint = checkpoint)),
            )
        assertPendingWithoutProgress(result)
        native.assertConsumed()
    }

    @Test
    fun `fresh native facts survive refused expansion and resume without replay`() = runTest {
        val f = QueryImpactExecutionFixture()
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val edge = f.edge(a, b, ValueTransferKind.LOCAL_BINDING)
        val native = f.script(listOf(ImpactReadExpectation(a, listOf(edge), work = 2), ImpactReadExpectation(b)))
        val service = f.service(native.port)
        val first = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(f.request(work = 3)))
        val saved = (first.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val savedImpact = assertInstanceOf(QueryImpactSnapshot::class.java, saved.impact)
        assertEquals(
            QueryValuePathAccountingStatus.EvidenceOnly,
            (first.result.rows as QueryRows.ValuePaths).accountingStatus,
        )
        assertEquals(listOf(a), native.examined)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in first.coverage.limitations)
        assertEquals(3L, (first.workUsage as QueryWorkUsage.Observed).count.value)
        assertEquals(emptyList<QueryImpactPath>(), savedImpact.paths)
        assertEquals(null, savedImpact.ledger)
        assertTrue((saved.tasks.first() as PipelineTask.ImpactExplore).route.steps.isEmpty())
        val observed = (savedImpact.reads.getValue(a) as ValueFlowRead.Observed).step
        assertEquals(listOf(edge), observed.transfers)
        assertEquals(1, savedImpact.receipts.getValue(a).size)
        assertSame(f.plan, saved.plan)
        assertSame(f.lease, saved.lease)

        val second =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(f.request(work = 3, checkpoint = saved)),
            )
        val next = (second.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val nextImpact = assertInstanceOf(QueryImpactSnapshot::class.java, next.impact)
        assertEquals(listOf(a), native.examined)
        assertSame(observed, (nextImpact.reads.getValue(a) as ValueFlowRead.Observed).step)
        assertEquals(b, (next.tasks.first() as PipelineTask.ImpactExplore).route.site)

        val last =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(f.request(work = 3, checkpoint = next)),
            )
        assertEquals(b, (last.result.rows as QueryRows.ValuePaths).values.single().destination)
        assertEquals(listOf(a, b), native.examined)
        assertEquals(listOf(2L, 2L), native.grants.map { it.resources.workUnitLimit.value })
        native.assertConsumed()
    }

    private fun assertPendingWithoutProgress(result: QueryExecutionResult.Qualified) {
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(emptyList<QueryImpactPath>(), rows.values)
        assertEquals(QueryValuePathAccountingStatus.EvidenceOnly, rows.accountingStatus)
        assertTrue(QueryLimitation.WORK_LIMIT_REACHED in result.coverage.limitations)
        assertEquals(QueryTerminalReason.NO_PROGRESS, (result.continuation as QueryContinuationState.Terminal).reason)
    }

    private fun cachedFanout(f: QueryImpactExecutionFixture): PipelineCheckpoint {
        val a = f.producer.site
        val b = f.site(30, ValueRole.LocalBinding)
        val c = f.site(40, ValueRole.LocalBinding)
        return checkpoint(
            f,
            listOf(
                observe(
                    f,
                    a,
                    listOf(
                        f.edge(a, b, ValueTransferKind.LOCAL_BINDING),
                        f.edge(a, c, ValueTransferKind.LOCAL_BINDING),
                    ),
                ),
                observe(f, b, emptyList()),
                observe(f, c, emptyList()),
            ),
        )
    }

    private fun checkpoint(f: QueryImpactExecutionFixture, observations: List<ValueFlowStep>) =
        PipelineCheckpoint(
            PipelineSeed.Accounted.create(f.plan),
            f.lease,
            listOf(
                PipelineTask.ImpactExplore(
                    QueryImpactRoute(f.producer.site, emptyList(), QueryImpactRepresentation.NotModeled)
                ),
                PipelineTask.ImpactFinalize,
            ),
            emptyMap(),
            QueryJoinSnapshot(emptyMap()),
            emptySet(),
            QueryCount.parse(0).value(),
            QueryImpactSnapshot(reads = observations.associate { it.source to ValueFlowRead.Observed(it) }),
        )

    private fun observe(f: QueryImpactExecutionFixture, source: ValueSite, edges: List<ValueTransfer>) =
        ValueFlowStep.fromCompiler(
                source,
                edges,
                emptyList(),
                ValueFlowTerminal.SupportedDomainExhausted,
                RelationRequest.start(
                    f.owner,
                    RelationMeaning.References,
                    RelationBudget(f.request().budget.resources, RelationByteLimit.parse(1000000).value()),
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                ),
                RelationWorkCount.parse(1).value(),
            )
            .value()
}

private fun <V> Refinement<V, *>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
    }
