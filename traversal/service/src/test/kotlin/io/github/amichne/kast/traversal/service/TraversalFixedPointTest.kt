package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalRecord
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached graph observations prove engine exhaustion and conservation, not native call resolution. */
class TraversalFixedPointTest {
    @Test
    fun `diamond and recursive edges survive while each reachable node expands once across resumes`() = runTest {
        val fixture = TraversalTestFixture()
        val a = fixture.selector("a", 0)
        val b = fixture.selector("b", 10)
        val c = fixture.selector("c", 20)
        val d = fixture.selector("d", 30)
        val graph = linkedMapOf(a to listOf(c, b), b to listOf(d, a), c to listOf(d), d to listOf(b))
        val expected = setOf("a:b:1", "a:c:1", "b:a:2", "b:d:2", "c:d:2", "d:b:3")
        val fullReader = InMemoryRelationReader(graph, fixture)
        val full =
            assertInstanceOf(
                TraversalResult.Complete::class.java,
                TraversalService(fullReader).run(fixture.plan(a)),
            )
        assertEquals(expected, full.page.records.map(::edge).toSet())
        assertEquals(6, full.page.records.size)

        val resumedReader = InMemoryRelationReader(graph.entries.reversed().associate { it.toPair() }, fixture)
        val plan = fixture.plan(a, frontier = 1)
        val resumed = exhaust(TraversalService(resumedReader), plan)
        assertEquals(expected, resumed.map(::edge).toSet())
        assertEquals(6, resumed.size)
        assertEquals(
            full.page.records.map { it.canonicalProjection() }.sorted(),
            resumed.map { it.canonicalProjection() }.sorted(),
        )
        val reachable =
            setOf(
                RelationEndpoint.subject(a).fingerprint,
                RelationEndpoint.subject(b).fingerprint,
                RelationEndpoint.subject(c).fingerprint,
                RelationEndpoint.subject(d).fingerprint,
            )
        for (reader in listOf(fullReader, resumedReader)) {
            assertEquals(reachable, reader.requests.map { it.node.fingerprint }.toSet())
            assertEquals(4, reader.requests.size)
            assertEquals(RelationEndpoint.subject(a).fingerprint, reader.requests.first().node.fingerprint)
            assertEquals(
                setOf(RelationEndpoint.subject(b).fingerprint, RelationEndpoint.subject(c).fingerprint),
                reader.requests.drop(1).take(2).map { it.node.fingerprint }.toSet(),
            )
            assertEquals(RelationEndpoint.subject(d).fingerprint, reader.requests.last().node.fingerprint)
        }
    }

    private suspend fun exhaust(service: TraversalService, initial: TraversalPlan): List<TraversalRecord> {
        var plan = initial
        val records = mutableListOf<TraversalRecord>()
        repeat(4) {
            when (val result = service.run(plan)) {
                is TraversalResult.Complete -> return records + result.page.records
                is TraversalResult.Rejected -> error("Unexpected rejection: ${result.reason}")
                is TraversalResult.Qualified -> {
                    records += result.page.records
                    val continuation =
                        assertInstanceOf(
                                TraversalQualification.Resumable::class.java,
                                result.qualification,
                            )
                            .continuation
                    val checkpoint = continuation.checkpoint
                    assertEquals(checkpoint.frontier.sorted(), checkpoint.frontier)
                    assertEquals(
                        checkpoint.frontier.size,
                        checkpoint.frontier.map { it.node.fingerprint }.distinct().size,
                    )
                    assertTrue(checkpoint.frontier.none { it.node.fingerprint in checkpoint.visited })
                    plan = TraversalPlan.resume(initial.start, initial.meaning, initial.budget, continuation).refined()
                }
            }
        }
        error("Four reachable nodes must exhaust within four frontier grants")
    }

    private fun edge(record: TraversalRecord): String =
        "${record.fact.source.name.value}:${record.related.name.value}:${record.depth.value}"
}
