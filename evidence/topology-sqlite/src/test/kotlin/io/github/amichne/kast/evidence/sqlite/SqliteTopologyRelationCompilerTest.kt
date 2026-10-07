package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverageFailure
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure published-content adapter behavior; this fixture asserts no physical publication or native compiler effects. */
class SqliteTopologyRelationCompilerTest {
    @Test
    fun `published pages one and five exhaust each meaning with the exact retained inventory`() = runTest {
        for (meaning in RelationMeaning.all) {
            for (limit in listOf(1, 5)) assertExhaustion(meaning, limit)
        }
    }

    private suspend fun assertExhaustion(meaning: RelationMeaning, limit: Int) {
        val fixture = topologyRelationFixture(meaning)
        val initial = fixture.request(limit)
        var request = initial
        var previous: RelationProviderState? = null
        val observed = mutableListOf<RelationFact>()
        var pages = 0
        while (pages < 8) {
            val result = fixture.compiler.read(request)
            val batch =
                when (result) {
                    is RelationCompilation.Complete -> result.batch
                    is RelationCompilation.Qualified -> result.batch
                    is RelationCompilation.Rejected -> error("Unexpected rejection: ${result.reason}")
                }
            assertTrue(batch.facts.size <= limit)
            batch.facts.forEach {
                assertSame(request.subject, it.subject)
                assertEquals(meaning, it.meaning)
                assertEquals(initial.subject.lease.identity, it.authority)
                assertEquals(RelationProvenance.K2_AUTHORED_SOURCE, it.provenance)
            }
            observed += batch.facts
            pages++
            if (result is RelationCompilation.Complete) break
            val continuation = assertProgress(result as RelationCompilation.Qualified, observed.size, previous)
            previous = continuation.providerState
            request = RelationRequest.resume(fixture.selector, meaning, initial.budget, continuation).refined()
        }
        assertEquals((7 + limit - 1) / limit, pages)
        assertEquals((20..26).toList(), observed.map { it.occurrence.range.startInclusive })
        assertEquals((21..27).toList(), observed.map { it.occurrence.range.endExclusive })
        assertEquals(List(7) { "Source" }, observed.map { it.source.name.value })
        assertEquals(List(7) { "Target" }, observed.map { it.target.name.value })
        assertEquals(7, observed.map { it.canonicalProjection() }.distinct().size)
        assertEquals(1, fixture.reads)
    }

    private fun assertProgress(
        result: RelationCompilation.Qualified,
        observed: Int,
        previous: RelationProviderState?,
    ): RelationContinuation {
        val coverage = assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, result.coverage)
        assertEquals(setOf(RelationLimitation.RESULT_LIMIT_REACHED), coverage.limitations)
        val continuation = coverage.continuation
        val retained = continuation.providerState
        assertEquals(RelationProviderKind.PUBLISHED_TOPOLOGY_V1, retained.provider)
        assertEquals(observed.toLong(), retained.consumedLocatorCount.value)
        assertEquals(observed + 1L, continuation.nextProviderCursor.nextPosition.value)
        assertEquals(7 - observed, retained.prepared.size)
        val next = assertInstanceOf(RelationProviderLocator.PublishedFact::class.java, retained.prepared.first())
        assertEquals(20 + observed, next.range.startInclusive)
        previous?.let {
            assertEquals(Refinement.Refined(retained), retained.advanceFrom(it))
            assertNotEquals(it.providerCursor.consumedPrefixDigest, retained.providerCursor.consumedPrefixDigest)
        }
        return continuation
    }

    @Test
    fun `same generation with a different published snapshot cannot consume retained facts`() = runTest {
        val original = topologyRelationFixture(RelationMeaning.Callees)
        val changed = topologyRelationFixture(RelationMeaning.Callees, sourceHash = "c")
        val initial = original.request(1)
        val first = assertInstanceOf(RelationCompilation.Qualified::class.java, original.compiler.read(initial))
        val continuation =
            assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, first.coverage).continuation
        val resumed = RelationRequest.resume(original.selector, initial.meaning, initial.budget, continuation).refined()
        assertEquals(original.selector.lease, changed.selector.lease)
        val retained = continuation.providerState
        val remaining = retained.prepared.toList()

        assertEquals(
            RelationCompilation.Rejected(RelationCompilerRejection.CONTINUATION_CURSOR_MOVED),
            changed.compiler.read(resumed),
        )
        assertEquals(remaining, retained.prepared)
        assertEquals(1L, retained.consumedLocatorCount.value)
        assertEquals(1, changed.reads)
    }

    @Test
    fun `an empty result cannot advertise another published leases retained inventory`() = runTest {
        val original = topologyRelationFixture(RelationMeaning.Callees)
        val foreign = topologyRelationFixture(RelationMeaning.Callees, generationNumber = 20)
        val first =
            assertInstanceOf(RelationCompilation.Qualified::class.java, original.compiler.read(original.request(1)))
        val retained =
            assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, first.coverage)
                .continuation
                .providerState
        val request = foreign.request(1)
        val emptyBatch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).refined(),
                    RelationWorkCount.parse(0).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH),
            RelationCompilation.qualifiedResumable(
                emptyBatch,
                setOf(RelationLimitation.WORK_LIMIT_REACHED),
                retained.providerCursor,
                retained,
            ),
        )
        val publication =
            assertInstanceOf(RelationProviderLocator.PublishedFact::class.java, retained.prepared.first()).publication
        val emptyInventory = RelationProviderState.publishedFacts(publication, emptyList()).refined()
        assertEquals(
            Refinement.Rejected(RelationIncompleteCoverageFailure.PROVIDER_STATE_AUTHORITY_MISMATCH),
            RelationCompilation.qualifiedResumable(
                emptyBatch,
                setOf(RelationLimitation.WORK_LIMIT_REACHED),
                emptyInventory.providerCursor,
                emptyInventory,
            ),
        )
    }

    @Test
    fun `a page whose first fact exceeds its byte budget is finitely terminal`() = runTest {
        val fixture = topologyRelationFixture(RelationMeaning.Callees)
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                fixture.compiler.read(fixture.request(1, bytes = 1)),
            )
        assertTrue(result.batch.facts.isEmpty())
        val coverage = assertInstanceOf(RelationIncompleteCoverage.TerminalIncomplete::class.java, result.coverage)
        assertEquals(setOf(RelationLimitation.BYTE_LIMIT_REACHED), coverage.limitations)
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected fixture refinement, got $failure")
    }
