package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthFailure
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalExtent
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalPlanResumeFailure
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalResumeFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached graph evidence exercises the real frontier engine, without claiming native resolution. */
class ExhaustiveTraversalTest {
    private val fixture = TraversalTestFixture()
    private val nodes = (0 until 20).map { fixture.selector("n$it", it * 10) }
    private val graph =
        nodes
            .mapIndexed { index, node ->
                node to
                    when (index) {
                        0 -> listOf(nodes[1], nodes[2])
                        1 -> listOf(nodes[3], nodes[0])
                        2 -> listOf(nodes[3])
                        19 -> listOf(nodes[3])
                        else -> listOf(nodes[index + 1])
                    }
            }
            .toMap()
    private val expected =
        setOf("n0:n1", "n0:n2", "n1:n3", "n1:n0", "n2:n3", "n19:n3") + (3 until 19).map { "n$it:n${it + 1}" }

    @Test
    fun `exhaustive chain diamond and cycles conserve edges beyond the former depth ceiling`() = runTest {
        val full = InMemoryRelationReader(graph, fixture)
        val complete = assertInstanceOf(TraversalResult.Complete::class.java, TraversalService(full).run(plan()))
        assertEquals(expected, complete.page.records.map(::edge).toSet())
        assertEquals(expected.size, complete.page.records.size)
        assertEquals(20, full.requests.size)
        assertEquals(19, complete.page.progress.maximumDepthReached)
        assertEquals(TraversalExtent.Exhaustive, complete.page.plan.budget.extent)

        val requests = mutableListOf<OneHopRelationRequest>()
        val paged = OneHopRelationReader { request ->
            requests += request
            val targets = graph.getValue(nodes.single { it.fingerprint.value == request.node.fingerprint.value })
            if (targets.size == 2 && request.position == OneHopRelationPosition.Start) {
                fixture.qualifiedRead(request, targets.take(1))
            } else {
                fixture.completeRead(
                    request,
                    if (request.position is OneHopRelationPosition.Resume) targets.drop(1) else targets,
                )
            }
        }
        val records = drain(TraversalService(paged), plan(frontier = 1, records = 1))
        assertEquals(expected, records.map(::edge).toSet())
        assertEquals(expected.size, records.size)
        assertEquals(
            complete.page.records.map(TraversalRecord::canonicalProjection).sorted(),
            records.map(TraversalRecord::canonicalProjection).sorted(),
        )
        val starts = requests.filter { it.position == OneHopRelationPosition.Start }
        assertEquals(20, starts.size)
        assertEquals(20, starts.map { it.node.fingerprint }.distinct().size)
        assertEquals(2, requests.count { it.position is OneHopRelationPosition.Resume })
    }

    @Test
    fun `explicit depth two remains a distinct bounded question`() = runTest {
        val reader = InMemoryRelationReader(graph, fixture)
        val bounded = fixture.plan(nodes[0], depth = 2)
        val result = assertInstanceOf(TraversalResult.Complete::class.java, TraversalService(reader).run(bounded))
        assertEquals(setOf("n0:n1", "n0:n2", "n1:n3", "n1:n0", "n2:n3"), result.page.records.map(::edge).toSet())
        assertEquals(setOf("n0", "n1", "n2"), reader.requests.map { it.node.endpoint.name.value }.toSet())
        assertFalse(result.page.records.any { it.related.name.value == "n19" })
    }

    @Test
    fun `resume cannot replace exhaustive question with bounded extent`() = runTest {
        val initial = plan(frontier = 1)
        val result =
            assertInstanceOf(
                TraversalResult.Qualified::class.java,
                TraversalService(InMemoryRelationReader(graph, fixture)).run(initial),
            )
        val continuation =
            assertInstanceOf(TraversalQualification.Resumable::class.java, result.qualification).continuation
        val changed =
            initial.budget.copy(extent = TraversalExtent.ThroughDepth(TraversalDepthLimit.parse(20).refined()))
        val rejected =
            assertInstanceOf(
                Refinement.Rejected::class.java,
                TraversalPlan.resume(nodes[0], RelationMeaning.Callees, changed, continuation),
            )
        assertEquals(TraversalPlanResumeFailure.Resume(TraversalResumeFailure.IDENTITY_MISMATCH), rejected.failure)
    }

    @Test
    fun `exhaustive frontier exhaustion retains permanent one hop incompleteness`() = runTest {
        val reader = OneHopRelationReader { request ->
            val relation =
                io.github.amichne.kast.relation.contract.RelationRequest.start(
                    nodes[0],
                    request.meaning,
                    request.budget,
                )
            OneHopRelationRead.Completed(
                fixture.terminalRelationResult(relation),
                OneHopElapsedMillis.parse(1).refined(),
            )
        }
        val result = assertInstanceOf(TraversalResult.Qualified::class.java, TraversalService(reader).run(plan()))
        assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, result.qualification)
        assertEquals(
            setOf(io.github.amichne.kast.traversal.contract.TraversalLimitation.ONE_HOP_INCOMPLETE),
            result.qualification.limitations,
        )
        assertTrue(result.page.records.isEmpty())
    }

    @Test
    fun `finite depth representation refuses overflow without shortening exhaustive semantics`() {
        val last = TraversalDepth.parse(Int.MAX_VALUE).refined()
        assertTrue(TraversalExtent.Exhaustive.permitsExpansion(last))
        val rejected = assertInstanceOf(Refinement.Rejected::class.java, last.next())
        assertEquals(TraversalDepthFailure.OVERFLOW, rejected.failure)
    }

    private fun plan(frontier: Int = 50, records: Int = 50): TraversalPlan {
        val bounded =
            fixture.plan(
                nodes[0],
                aggregateRecords = records,
                frontier = frontier,
                oneHop = fixture.relationBudget(records = minOf(records, 2)),
            )
        return TraversalPlan.start(
                nodes[0],
                RelationMeaning.Callees,
                bounded.budget.copy(extent = TraversalExtent.Exhaustive),
            )
            .refined()
    }

    private suspend fun drain(service: TraversalService, initial: TraversalPlan): List<TraversalRecord> {
        var request = initial
        val records = mutableListOf<TraversalRecord>()
        repeat(25) {
            when (val result = service.run(request)) {
                is TraversalResult.Complete -> return records + result.page.records
                is TraversalResult.Rejected -> error("Unexpected rejection: ${result.reason}")
                is TraversalResult.Qualified -> {
                    records += result.page.records
                    val continuation =
                        assertInstanceOf(TraversalQualification.Resumable::class.java, result.qualification)
                            .continuation
                    request =
                        TraversalPlan.resume(initial.start, initial.meaning, initial.budget, continuation).refined()
                }
            }
        }
        error("Twenty reachable nodes and two retained relation pages must exhaust")
    }

    private fun edge(record: TraversalRecord): String = "${record.fact.source.name.value}:${record.related.name.value}"
}
