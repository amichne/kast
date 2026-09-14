package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalQualification
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraversalReplayTest {
    private val fixture = TraversalTestFixture()
    private val a = fixture.selector("a", 10)
    private val b = fixture.selector("b", 20)
    private val c = fixture.selector("c", 30)

    @Test
    fun `higher budget resume and replay preserve repeated occurrences and reference graph`() = runTest {
        val referenceReader =
            InMemoryRelationReader(
                linkedMapOf(a to listOf(b, b), b to listOf(c), c to listOf(a)),
                fixture,
            )
        val reference =
            assertInstanceOf(
                TraversalResult.Complete::class.java,
                TraversalService(referenceReader).run(fixture.plan(a)),
            )
        val requests = mutableListOf<OneHopRelationRequest>()
        val reader = occurrenceReader(requests)
        val service = TraversalService(reader)
        val limited = fixture.plan(a, aggregateRecords = 1, oneHop = fixture.relationBudget(records = 1))
        val first = assertInstanceOf(TraversalResult.Qualified::class.java, service.run(limited))
        val qualification = assertInstanceOf(TraversalQualification.Resumable::class.java, first.qualification)
        val partial = first.page.partialExpansions.single()
        assertEquals(a.fingerprint.value, partial.entry.node.fingerprint.value)
        assertEquals(0, partial.entry.depth.value)
        assertEquals(setOf(RelationLimitation.PROVIDER_INCOMPLETE), partial.limitations)
        assertEquals(
            io.github.amichne.kast.traversal.contract.TraversalExpansionRemainder.CONTINUATION_RETAINED,
            partial.remainder,
        )
        assertEquals(1L, first.page.progress.checkpointSequence)
        assertEquals(1L, first.page.progress.totalReads)
        assertEquals(1L, first.page.progress.totalEdges)
        assertEquals(first.page.progress, qualification.continuation.checkpoint.progress)
        val resumedPlan =
            TraversalPlan.resume(
                    selector = a,
                    meaning = RelationMeaning.Callees,
                    budget = fixture.plan(a).budget,
                    continuation = qualification.continuation,
                )
                .refined()
        val resumed = assertInstanceOf(TraversalResult.Complete::class.java, service.run(resumedPlan))
        val replay = assertInstanceOf(TraversalResult.Complete::class.java, service.run(resumedPlan))
        assertResumedEvidence(reference, first, resumed, replay)
        assertEquals(7, requests.size)
        assertTrue(requests.drop(1).all { it.budget == resumedPlan.budget.oneHop })
    }

    private fun occurrenceReader(requests: MutableList<OneHopRelationRequest>): OneHopRelationReader =
        OneHopRelationReader { request ->
            requests += request
            when (request.node.fingerprint.value) {
                a.fingerprint.value ->
                    when (request.position) {
                        OneHopRelationPosition.Start -> fixture.qualifiedRead(request, listOf(b))
                        is OneHopRelationPosition.Resume ->
                            fixture.completeRead(request, listOf(b), occurrenceOffset = 1)
                    }
                b.fingerprint.value -> fixture.completeRead(request, listOf(c))
                else -> fixture.completeRead(request, listOf(a))
            }
        }

    private fun assertResumedEvidence(
        reference: TraversalResult.Complete,
        first: TraversalResult.Qualified,
        resumed: TraversalResult.Complete,
        replay: TraversalResult.Complete,
    ) {
        val combined = first.page.records + resumed.page.records
        assertEquals(reference.page.records.map { it.canonicalProjection() }, combined.map { it.canonicalProjection() })
        assertEquals(4, combined.size)
        val rootOccurrences = combined.filter { it.origin.value == a.fingerprint.value }
        assertEquals(2, rootOccurrences.size)
        assertEquals(1, rootOccurrences.map { it.related.fingerprint }.distinct().size)
        assertEquals(2, rootOccurrences.map { it.fact.occurrence }.distinct().size)
        assertEquals(3, resumed.coverage.exactRecordCount.value)
        assertEquals(2L, resumed.page.progress.checkpointSequence)
        assertEquals(4L, resumed.page.progress.totalReads)
        assertEquals(4L, resumed.page.progress.totalEdges)
        assertEquals(reference.page.progress.maximumDepthReached, resumed.page.progress.maximumDepthReached)
        assertTrue(resumed.page.partialExpansions.isEmpty())
        assertEquals(
            resumed.page.records.map { it.canonicalProjection() },
            replay.page.records.map { it.canonicalProjection() },
        )
        assertEquals(resumed.page.progress, replay.page.progress)
        assertEquals(resumed.page.partialExpansions, replay.page.partialExpansions)
        assertEquals(resumed.coverage, replay.coverage)
    }
}
