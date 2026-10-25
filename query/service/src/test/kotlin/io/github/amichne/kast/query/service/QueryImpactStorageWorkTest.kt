package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryImpactExecutionStop
import io.github.amichne.kast.query.contract.QueryImpactProducer
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactStorageWorkTest {
    @Test
    fun `runner keeps every result and grant without whole checkpoint scans`() = runTest {
        val fixture = QueryImpactExecutionFixture()
        val producers =
            (0 until 64).map { offset ->
                val invocation = fixture.call("produce", 20 + offset, 21 + offset)
                QueryImpactProducer.admit(invocation.resultSite(), invocation).refined()
            }
        val bounded =
            fixture.request(
                work = 256,
                results = 64,
                plan = fixture.plan(producers = producers),
                checkpointBytes = 1_000_000_000L,
            )
        val runner =
            QueryExecutionRequest.create(
                    bounded.plan,
                    bounded.lease,
                    bounded.budget.copy(resources = ResourceBudget.RunnerControlled),
                )
                .refined()

        val boundedScans = explore(fixture, bounded)
        val runnerScans = explore(fixture, runner)

        assertEquals(128, boundedScans, "Bounded acquisition and expansion must each inspect current storage")
        assertEquals(0, runnerScans, "Unlimited storage must not inspect the whole checkpoint for each read")
    }

    @Test
    fun `bounded capacity still stops before the native read`() = runTest {
        val fixture = QueryImpactExecutionFixture()
        val request = fixture.request(checkpointBytes = 1)
        val tasks = ArrayDeque(initialTasks(request.plan))
        val state = QueryExecutionState(request, QueryNanoClock { 0L })
        val native = fixture.script(emptyList())
        val owner = QueryImpactTasks(request, state, tasks, native.port, null)
        val task = assertInstanceOf(PipelineTask.ImpactExplore::class.java, tasks.first())
        var scans = 0

        val transition =
            owner.explore(task) { graph ->
                scans++
                owner.snapshot().retainedBytes(graph)
            }

        assertSame(QueryImpactTaskTransition.Advanced, transition)
        assertEquals(1, scans)
        assertTrue(owner.snapshot().reads.isEmpty())
        val path = owner.snapshot().paths.single()
        val terminal = assertInstanceOf(QueryImpactTerminal.Unresolved.ExecutionStop::class.java, path.terminal)
        val stop = assertInstanceOf(QueryImpactExecutionStop.CheckpointCapacity::class.java, terminal.stop)
        assertEquals(fixture.producer.site, stop.source)
        assertEquals(1L, stop.available.value)
        assertTrue(stop.required.value > stop.available.value)
        native.assertConsumed()
    }

    private suspend fun explore(fixture: QueryImpactExecutionFixture, request: QueryExecutionRequest): Int {
        val plan = assertInstanceOf(AdmittedQueryPlan.Impact::class.java, request.plan)
        val sites = plan.source.producers.map { it.site }
        val native = fixture.script(sites.map(::ImpactReadExpectation))
        val tasks = ArrayDeque(initialTasks(request.plan))
        val state = QueryExecutionState(request, QueryNanoClock { 0L })
        val owner = QueryImpactTasks(request, state, tasks, native.port, null)
        var scans = 0
        repeat(sites.size) {
            val task = assertInstanceOf(PipelineTask.ImpactExplore::class.java, tasks.first())
            val transition =
                owner.explore(task) { graph ->
                    scans++
                    PipelineCheckpoint(
                            PipelineSeed.Accounted.create(request.plan),
                            request.lease,
                            tasks.toList(),
                            emptyMap(),
                            QueryJoinSnapshot(emptyMap()),
                            emptySet(),
                            0.queryCount(),
                            owner.snapshot(),
                        )
                        .retainedBytes(graph)
                }
            assertSame(QueryImpactTaskTransition.Advanced, transition)
        }
        assertSame(PipelineTask.ImpactFinalize, tasks.single())
        assertSame(QueryImpactTaskTransition.Advanced, owner.finalizeInvestigation())
        val rows = assertInstanceOf(QueryImpactRowsState.Ready::class.java, owner.rowsState()).rows
        assertEquals(sites, rows.values.map { it.producer })
        assertEquals(sites, rows.values.map { it.destination })
        assertEquals(sites, owner.snapshot().reads.keys.toList())
        assertEquals(sites.size.toLong(), state.consumedWork().value)
        assertTrue(state.limitations.isEmpty())
        rows.values.forEach { path ->
            assertTrue(path.steps.isEmpty())
            val terminal = assertInstanceOf(QueryImpactTerminal.SupportedDomainEnd::class.java, path.terminal)
            assertEquals(request.lease.identity, terminal.observation.source.basis)
            assertEquals(plan.source.domain, terminal.observation.domain.boundary)
        }
        assertRunnerGrants(request, native.grants)
        assertEquals(sites, native.examined)
        native.assertConsumed()
        return scans
    }

    private fun assertRunnerGrants(
        request: QueryExecutionRequest,
        grants: List<io.github.amichne.kast.relation.contract.RelationBudget>,
    ) {
        if (request.budget.resources == ResourceBudget.RunnerControlled) {
            grants.forEach { grant ->
                assertEquals(ResourceBudget.RunnerControlled, grant.resources)
                assertEquals(request.budget.outputResults.value, grant.outputResults.value)
                assertEquals(request.budget.returnedBytes.value, grant.returnedBytes.value)
            }
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture refinement rejected: $failure")
        }
}
