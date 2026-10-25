package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class QueryImpactFinalizedSnapshotTest {
    @Test
    fun `output only restoration keeps the finalized inventory without visits`(reporter: TestReporter) = runTest {
        val fixture = QueryImpactExecutionFixture()
        val producers =
            listOf(30, 40).map { offset ->
                val call = fixture.call("produce", offset, offset + 1)
                QueryImpactProducer.admit(call.resultSite(), call).refined()
            }
        val bounded = fixture.request(plan = fixture.plan(producers = producers))
        val request =
            QueryExecutionRequest.create(
                    bounded.plan,
                    bounded.lease,
                    bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                )
                .refined()
        val tasks = ArrayDeque(initialTasks(request.plan))
        val native = fixture.script(producers.map { ImpactReadExpectation(it.site) })
        val owner =
            QueryImpactTasks(request, QueryExecutionState(request, QueryNanoClock { 0L }), tasks, native.port, null)
        repeat(producers.size) {
            val task = assertInstanceOf(PipelineTask.ImpactExplore::class.java, tasks.first())
            assertSame(QueryImpactTaskTransition.Advanced, owner.explore(task) { error("Unexpected storage scan") })
        }
        assertSame(QueryImpactTaskTransition.Advanced, owner.finalizeInvestigation())
        native.assertConsumed()
        assertTrue(tasks.all { it is PipelineTask.ValuePath })
        val finalized = owner.snapshot()
        val reads = CountedMap(finalized.reads)
        val saved = finalized.copy(reads = reads)
        val noReads = fixture.script(emptyList())

        val restored =
            QueryImpactTasks(request, QueryExecutionState(request, QueryNanoClock { 0L }), tasks, noReads.port, saved)
        val rows = assertInstanceOf(QueryImpactRowsState.Ready::class.java, restored.rowsState()).rows
        val snapshot = restored.snapshot()

        reporter.publishEntry("finalized-read-entry-visits", reads.visits.toString())
        assertEquals(0, reads.visits, "A finalized ledger needs no read inventory for output-only tasks")
        assertSame(saved, snapshot)
        assertSame(saved.ledger, (rows.accounting as QueryValuePathAccounting.Investigated).ledger)
        assertSame(saved.reads, snapshot.reads)
        assertSame(saved.receipts, snapshot.receipts)
        assertSame(saved.remainders, snapshot.remainders)
        assertSame(saved.readRejections, snapshot.readRejections)
        assertSame(saved.paths, snapshot.paths)
        assertEquals(producers.map { it.site }, rows.values.map { it.producer })
        noReads.assertConsumed()
    }

    private class CountedMap<Key, Value>(private val original: Map<Key, Value>) : AbstractMap<Key, Value>() {
        var visits = 0
            private set

        override val entries: Set<Map.Entry<Key, Value>> =
            object : AbstractSet<Map.Entry<Key, Value>>() {
                override val size: Int = original.size

                override fun iterator(): Iterator<Map.Entry<Key, Value>> =
                    original.entries.asSequence().onEach { visits++ }.iterator()
            }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
        }
}
