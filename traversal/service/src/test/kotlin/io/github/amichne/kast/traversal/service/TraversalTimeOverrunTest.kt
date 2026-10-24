package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalQualificationFailure
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import io.github.amichne.kast.traversal.contract.TraversalStrategy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class TraversalTimeOverrunTest {
    private val fixture = TraversalTestFixture()
    private val root = fixture.selector("root", 0)
    private val child = fixture.selector("child", 1)

    @Test
    fun `observed overrun retains committed facts and pending frontier without another read`() {
        var nanos = 0L
        var reads = 0
        val relations = RelationOperations { request ->
            reads += 1
            nanos += 6_000_000L
            fixture.completeRelationResult(request, listOf(fixture.endpoint(request.subject, child)))
        }
        val plan = fixture.plan(root, aggregateTime = 5L, oneHop = fixture.relationBudget(time = 5L))
        val observed = runSuspend { traversalOperations(relations, TraversalNanoClock { nanos }).run(plan) }
        val result = assertInstanceOf(TraversalResult.Qualified::class.java, observed, observed.toString())
        assertEquals(1, reads)
        assertEquals(6L, result.page.elapsedMillis.value)
        assertEquals(1, result.page.records.size)
        assertEquals(setOf(TraversalLimitation.TIME_LIMIT_REACHED), result.qualification.limitations)
        val resume = assertInstanceOf(TraversalQualification.Resumable::class.java, result.qualification)
        assertEquals(1L, resume.continuation.checkpoint.progress.checkpointSequence)
        assertEquals(child.fingerprint.value, resume.continuation.checkpoint.frontier.single().node.fingerprint.value)
        assertInstanceOf(
            Refinement.Refined::class.java,
            TraversalPlan.resume(
                selector = root,
                meaning = RelationMeaning.Callees,
                budget = plan.budget,
                continuation = resume.continuation,
            ),
        )
    }

    @Test
    fun `exhausted overrun is terminal time qualified and cannot manufacture complete coverage`() {
        var nanos = 0L
        val relations = RelationOperations { request ->
            nanos += 6_000_000L
            fixture.completeRelationResult(request, emptyList())
        }
        val plan = fixture.plan(root, aggregateTime = 5L, oneHop = fixture.relationBudget(time = 5L))
        val observed = runSuspend { traversalOperations(relations, TraversalNanoClock { nanos }).run(plan) }
        val result = assertInstanceOf(TraversalResult.Qualified::class.java, observed, observed.toString())
        assertEquals(6L, result.page.elapsedMillis.value)
        assertEquals(setOf(TraversalLimitation.TIME_LIMIT_REACHED), result.qualification.limitations)
        assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, result.qualification)
        assertEquals(
            TraversalResult.Rejected(TraversalRejection.TraversalContractViolation),
            TraversalResult.complete(result.page),
        )
        assertEquals(
            Refinement.Rejected(TraversalQualificationFailure.ELAPSED_OVERRUN_UNQUALIFIED),
            TraversalResult.qualifiedTerminal(result.page, setOf(TraversalLimitation.DEPTH_LIMIT_REACHED), emptySet()),
        )
    }

    @TestFactory
    fun `requested depth does not hide an observed time overrun or invent resumable work`() =
        listOf(TraversalStrategy.BreadthFirst, TraversalStrategy.BoundedFanOut(ResultLimit.parse(1).refined())).map {
            strategy ->
            dynamicTest(strategy.toString()) {
                val fixture = TraversalTestFixture()
                val root = fixture.selector("root", 0)
                val child = fixture.selector("child", 10)
                val reads = mutableListOf<OneHopRelationRequest>()
                val reader = OneHopRelationReader { request ->
                    reads += request
                    fixture.completeRead(request, listOf(child), elapsedMillis = 6L)
                }
                val budget =
                    fixture.plan(root, depth = 1, aggregateTime = 5L, oneHop = fixture.relationBudget(time = 5L)).budget
                val plan = TraversalPlan.start(root, RelationMeaning.Callees, budget, strategy).refined()
                val result =
                    assertInstanceOf(
                        TraversalResult.Qualified::class.java,
                        runSuspend { TraversalService(reader).run(plan) },
                    )
                assertEquals(1, reads.size)
                assertEquals(6L, result.page.elapsedMillis.value)
                assertEquals(listOf(child.fingerprint.value), result.page.records.map { it.related.fingerprint.value })
                assertEquals(setOf(TraversalLimitation.TIME_LIMIT_REACHED), result.qualification.limitations)
                assertInstanceOf(TraversalQualification.TerminalIncomplete::class.java, result.qualification)
            }
        }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
