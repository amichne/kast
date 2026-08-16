package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class TraversalServiceTest {
    private val fixture = TraversalTestFixture()
    private val a = fixture.selector("a", 10)
    private val b = fixture.selector("b", 20)
    private val c = fixture.selector("c", 30)

    @Test
    fun `cycles terminate once and equivalent graph insertion orders are deterministic`() {
        val firstReader = InMemoryRelationReader(
            linkedMapOf(a to listOf(c, b), b to listOf(c, a), c to listOf(a)),
            fixture,
        )
        val secondReader = InMemoryRelationReader(
            linkedMapOf(c to listOf(a), b to listOf(a, c), a to listOf(b, c)),
            fixture,
        )
        val plan = fixture.plan(a)

        val first = assertInstanceOf(
            TraversalResult.Complete::class.java,
            runSuspend { TraversalService(firstReader).run(plan) },
        )
        val second = assertInstanceOf(
            TraversalResult.Complete::class.java,
            runSuspend { TraversalService(secondReader).run(plan) },
        )

        assertEquals(5, first.coverage.exactRecordCount.value)
        assertEquals(
            first.page.records.map { it.canonicalProjection() },
            second.page.records.map { it.canonicalProjection() },
        )
        assertEquals(3, firstReader.requests.size)
        assertEquals(
            3,
            firstReader.requests.map { it.node.fingerprint }.distinct().size,
        )
        assertTrue(firstReader.requests.all { it.meaning == RelationMeaning.Callees })
        assertTrue(firstReader.requests.all { it.scope == plan.scope })
        assertTrue(firstReader.requests.all { it.budget == plan.budget.oneHop })
    }

    @Test
    fun `every explicit aggregate bound qualifies with a continuation`() {
        val graph = linkedMapOf(a to listOf(b, c), b to emptyList(), c to emptyList())
        val oneHop = fixture.relationBudget(records = 2, bytes = 100_000L, work = 10L, time = 10L)
        val cases = listOf(
            TraversalLimitation.RECORD_LIMIT_REACHED to fixture.plan(
                a,
                aggregateRecords = 2,
                oneHop = oneHop,
            ),
            TraversalLimitation.BYTE_LIMIT_REACHED to fixture.plan(
                a,
                aggregateBytes = 100_000L,
                oneHop = oneHop,
            ),
            TraversalLimitation.WORK_LIMIT_REACHED to fixture.plan(
                a,
                aggregateWork = 10L,
                oneHop = oneHop,
            ),
            TraversalLimitation.TIME_LIMIT_REACHED to fixture.plan(
                a,
                aggregateTime = 10L,
                oneHop = oneHop,
            ),
            TraversalLimitation.DEPTH_LIMIT_REACHED to fixture.plan(
                a,
                depth = 1,
                oneHop = oneHop,
            ),
            TraversalLimitation.FRONTIER_LIMIT_REACHED to fixture.plan(
                a,
                frontier = 1,
                oneHop = oneHop,
            ),
        )

        cases.forEach { (expected, plan) ->
            val result = assertInstanceOf(
                TraversalResult.Qualified::class.java,
                runSuspend { TraversalService(InMemoryRelationReader(graph, fixture)).run(plan) },
                expected.name,
            )
            assertEquals(setOf(expected), result.qualification.limitations, expected.name)
            assertEquals(plan.identity, result.qualification.continuation.identity, expected.name)
        }
    }

    @Test
    fun `explicitly larger depth resumes the exact stopped frontier`() {
        val graph = linkedMapOf(a to listOf(b), b to emptyList())
        val reader = InMemoryRelationReader(graph, fixture)
        val shallow = fixture.plan(a, depth = 1)
        val stopped = assertInstanceOf(
            TraversalResult.Qualified::class.java,
            runSuspend { TraversalService(reader).run(shallow) },
        )
        val wider = fixture.plan(a, depth = 2)
        val resumed = TraversalPlan.resume(
            a,
            RelationMeaning.Callees,
            wider.budget,
            stopped.qualification.continuation,
        ).refined()

        val complete = assertInstanceOf(
            TraversalResult.Complete::class.java,
            runSuspend { TraversalService(reader).run(resumed) },
        )

        assertEquals(0, complete.coverage.exactRecordCount.value)
        assertEquals(2, reader.requests.size)
    }

    @Test
    fun `incomplete one hop evidence remains qualified with relation continuation`() {
        val reader = InMemoryRelationReader(
            linkedMapOf(a to listOf(b), b to emptyList()),
            fixture,
            qualified = setOf(a.fingerprint.value),
        )

        val result = assertInstanceOf(
            TraversalResult.Qualified::class.java,
            runSuspend { TraversalService(reader).run(fixture.plan(a)) },
        )

        assertEquals(
            setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
            result.qualification.limitations,
        )
        assertEquals(
            setOf(RelationLimitation.PROVIDER_INCOMPLETE),
            result.qualification.relationLimitations,
        )
        val pending = assertInstanceOf(
            TraversalPendingState.Active::class.java,
            result.qualification.continuation.checkpoint.pending,
        )
        assertEquals(a.fingerprint.value, pending.read.entry.node.fingerprint.value)
    }

    @Test
    fun `reader cannot widen identity scope meaning or one hop budget`() {
        val plan = fixture.plan(a)
        val escalatingReader = OneHopRelationReader { request ->
            fixture.completeRead(request, b, emptyList())
        }

        val result = runSuspend { TraversalService(escalatingReader).run(plan) }

        assertEquals(
            TraversalResult.Rejected(TraversalRejection.ReaderContractViolation),
            result,
        )
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
