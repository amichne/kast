package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndexedTopologyRelationCompilerTest {
    @Test
    fun `location distinct compiler identities retain exact directional subjects before page capacity`() = runTest {
        val outgoing = topologyRelationFixture(RelationMeaning.Callees, mixedGraph = true)
        val complete =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                outgoing.compiler.read(outgoing.request(7, work = 7)),
            )
        assertEquals((20..26).toList(), complete.batch.facts.map { it.occurrence.range.startInclusive })
        assertEquals(
            List(7) { "/workspace/src/main/kotlin/Source.kt" },
            complete.batch.facts.map { it.source.file.stableValue },
        )
        assertEquals(
            List(7) { "/workspace/src/main/kotlin/Target.kt" },
            complete.batch.facts.map { it.target.file.stableValue },
        )

        val duplicate = topologyRelationFixture(RelationMeaning.Callees, mixedGraph = true, subjectFile = "Duplicate")
        val duplicateCalls =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                duplicate.compiler.read(duplicate.request(19, work = 19)),
            )
        assertEquals((1..19).toList(), duplicateCalls.batch.facts.map { it.occurrence.range.startInclusive })
        assertEquals(
            List(19) { "/workspace/src/main/kotlin/Duplicate.kt" },
            duplicateCalls.batch.facts.map { it.source.file.stableValue },
        )

        val incoming = topologyRelationFixture(RelationMeaning.Callers, mixedGraph = true, subjectName = "Source")
        val callers =
            assertInstanceOf(
                RelationCompilation.Complete::class.java,
                incoming.compiler.read(incoming.request(1, work = 1)),
            )
        assertEquals(listOf(40), callers.batch.facts.map { it.occurrence.range.startInclusive })
        assertEquals(listOf("Target"), callers.batch.facts.map { it.source.name.value })
        assertEquals(
            listOf("/workspace/src/main/kotlin/Source.kt"),
            callers.batch.facts.map { it.target.file.stableValue },
        )
    }

    @Test
    fun `mixed graph page sizes preserve independently expected eligible occurrences`() = runTest {
        for (limit in listOf(1, 3, 7)) assertMixedGraphExhaustion(limit)
    }

    private suspend fun assertMixedGraphExhaustion(limit: Int) {
        val fixture = topologyRelationFixture(RelationMeaning.Callees, mixedGraph = true)
        var request = fixture.request(limit, work = limit.toLong())
        val occurrences = mutableListOf<Int>()
        var pages = 0
        while (true) {
            val result = fixture.compiler.read(request)
            val batch =
                when (result) {
                    is RelationCompilation.Complete -> result.batch
                    is RelationCompilation.Qualified -> result.batch
                    is RelationCompilation.Rejected -> error("Unexpected rejection: ${result.reason}")
                }
            occurrences += batch.facts.map { it.occurrence.range.startInclusive }
            pages++
            assertTrue(pages <= 7, "Eligible inventory must make bounded progress")
            if (result is RelationCompilation.Complete) break
            val continuation =
                assertInstanceOf(
                        RelationIncompleteCoverage.Resumable::class.java,
                        (result as RelationCompilation.Qualified).coverage,
                    )
                    .continuation
            request = RelationRequest.resume(fixture.selector, fixture.meaning, request.budget, continuation).refined()
        }
        assertEquals((20..26).toList(), occurrences)
        assertEquals((7 + limit - 1) / limit, pages)
        assertEquals(1, fixture.reads)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected fixture refinement, got $failure")
    }
