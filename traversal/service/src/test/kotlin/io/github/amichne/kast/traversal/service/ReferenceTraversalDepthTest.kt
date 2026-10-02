package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceTraversalDepthTest {
    @Test
    fun `reference walk reaches its third depth when all three providers complete`() {
        val fixture = TraversalTestFixture()
        val a = fixture.selector("a", 10)
        val b = fixture.selector("b", 20)
        val c = fixture.selector("c", 30)
        val d = fixture.selector("d", 40)
        val subjects = mutableListOf<String>()
        val relations = RelationOperations { request ->
            subjects += request.subject.name.value
            val related =
                when (request.subject.fingerprint.value) {
                    a.fingerprint.value -> b
                    b.fingerprint.value -> c
                    c.fingerprint.value -> d
                    else -> error("The depth-three request must not expand its depth-three endpoint")
                }
            fixture.completeRelationResult(request, listOf(fixture.endpoint(request.subject, related)))
        }
        val plan = TraversalPlan.start(a, RelationMeaning.References, fixture.plan(a, depth = 3).budget).refined()

        val result =
            assertInstanceOf(
                TraversalResult.Complete::class.java,
                runSuspend { traversalOperations(relations, TraversalNanoClock { 0L }).run(plan) },
            )

        assertEquals(listOf("a", "b", "c"), subjects)
        assertEquals(listOf("b", "c", "d"), result.page.records.map { it.related.name.value })
        assertEquals(listOf(1, 2, 3), result.page.records.map { it.depth.value })
        assertEquals(3, result.page.progress.maximumDepthReached)
        assertEquals(3L, result.page.progress.totalReads)
        assertTrue(result.page.partialExpansions.isEmpty())
        assertEquals(3, result.coverage.exactRecordCount.value)
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
