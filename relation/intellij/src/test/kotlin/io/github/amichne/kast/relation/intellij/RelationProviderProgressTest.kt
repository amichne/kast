package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderPosition
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached producer/collector rules only. Installed definitions and callee fixtures establish native K2 behavior. */
class RelationProviderProgressTest {
    @Test
    fun `definitions and callees exhaust one five and twenty without consuming a prefix again`() {
        for (meaning in
            listOf(
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides,
                RelationMeaning.Callees,
            )) {
            for (limit in listOf(1, 5, 20)) exhaust(meaning, limit)
        }
    }

    private fun exhaust(meaning: RelationMeaning, limit: Int) {
        val run = ProviderRun(meaning, limit)
        var pages = 0
        while (true) {
            val result = run.page()
            assertTrue(++pages <= 133)
            if (result is RelationCompilation.Complete) break
            run.resume(result as RelationCompilation.Qualified)
        }
        run.assertExhaustion(pages)
    }

    @Test
    fun `a restored consumed ordinal is observed while the production loop visits the remaining suffix`() {
        val request = resumeAfterFirstFilteredItem()
        val admitted = (request.position as RelationReadPosition.Resume).continuation.providerState.consumedLocatorCount
        val observation = Observation()
        val collector = IntellijRelationCollector(request, { 0L }, observation)
        val actualVisits = mutableListOf<Long>()
        val restoredVisits = mutableListOf<Long>()
        val provider =
            readRelationInventory(
                request,
                collector,
                prepare = { error("A successor must not prepare another native inventory") },
                confirm = { state, locator ->
                    actualVisits += locator.descriptor.value.substringAfter(':').toLong()
                    restoredVisits += 0L
                    observeRelationLocatorRestoration(request, RelationProviderPosition.Zero, observation)
                    restoredVisits += state.consumedLocatorCount.value
                    observeRelationLocatorRestoration(request, state.consumedLocatorCount, observation)
                    collector.dismissProviderItem()
                },
                cancellationCheck = {},
                observation = observation,
            )
        assertEquals(ProviderTermination.HALTED, provider)
        assertEquals(listOf(1L), actualVisits)
        assertEquals(listOf(0L, 1L), restoredVisits)
        assertEquals(1, observation.initialized)
        assertEquals(
            restoredVisits.count { it < admitted.value },
            observation.replayedPrefix,
        )
        assertEquals(1, observation.replayedPrefix)
    }

    private fun resumeAfterFirstFilteredItem(): RelationRequest {
        val fixture = RelationReadTest()
        val initial = fixture.request(RelationMeaning.Callees, workLimit = 1)
        val inventory = detachedRelationInventory(initial, listOf("provider:0", "provider:1", "provider:2"))
        val first = IntellijRelationCollector(initial, { 0L })
        assertTrue(first.retainProviderState(inventory, preparedPartition = true))
        assertEquals(
            IntellijRelationProviderItemAdmission.READY,
            first.beginProviderItem(inventory.prepared.first().descriptor),
        )
        assertTrue(first.dismissProviderItem())
        assertTrue(first.retainProviderState(inventory.consume()))
        val page = first.finish(IntellijRelationTermination.Resumable(emptySet())) as RelationCompilation.Qualified
        val continuation = (page.coverage as RelationIncompleteCoverage.Resumable).continuation
        return RelationRequest.resume(
                (initial.subject as RelationEndpoint.Subject).selector,
                initial.meaning,
                initial.budget,
                continuation,
            )
            .value()
    }

    private inner class ProviderRun(meaning: RelationMeaning, limit: Int) {
        private val fixture = RelationReadTest()
        private val initial = fixture.request(meaning, resultLimit = limit, workLimit = 5)
        private val descriptors = (0 until 132).map { "provider:${it.toString().padStart(3, '0')}" }
        private val ranges = (0 until 132).map { exactFixtureRange(it * 4, it * 4 + 3) }
        private val inventory = detachedRelationInventory(initial, descriptors, ranges)
        private var request = initial
        private val facts = mutableListOf<RelationFact>()
        private val consumed = mutableListOf<String>()
        private val observation = Observation()
        private var preparations = 0
        private val visitedOrdinals = mutableListOf<Long>()

        fun page(): RelationCompilation {
            val collector = IntellijRelationCollector(request, { 0L }, observation)
            val provider =
                readRelationInventory(
                    request,
                    collector,
                    prepare = {
                        assertEquals(0, preparations++)
                        assertTrue(collector.retainProviderState(inventory, preparedPartition = true))
                        RelationInventoryPreparation.Prepared(inventory)
                    },
                    confirm = { state, locator -> confirm(collector, state, locator) },
                    cancellationCheck = {},
                    observation = observation,
                )
            val result =
                collector.finish(
                    if (provider == ProviderTermination.HALTED) IntellijRelationTermination.Resumable(emptySet())
                    else IntellijRelationTermination.Terminal
                )
            facts +=
                when (result) {
                    is RelationCompilation.Complete -> result.batch.facts
                    is RelationCompilation.Qualified -> result.batch.facts
                    is RelationCompilation.Rejected -> error("Detached producer rejected: ${result.reason}")
                }
            return result
        }

        private fun confirm(
            collector: IntellijRelationCollector,
            state: RelationProviderState,
            locator: RelationProviderLocator,
        ): Boolean {
            val ordinal = locator.descriptor.value.substringAfter(':').toInt()
            val admitted =
                when (val selected = request.position) {
                    RelationReadPosition.Start -> RelationProviderPosition.Zero
                    is RelationReadPosition.Resume -> selected.continuation.providerState.consumedLocatorCount
                }
            assertEquals(ordinal.toLong(), state.consumedLocatorCount.value)
            assertTrue(ordinal >= admitted.value)
            visitedOrdinals += ordinal.toLong()
            observeRelationLocatorRestoration(request, state.consumedLocatorCount, observation)
            assertEquals(descriptors[consumed.size], locator.descriptor.value)
            consumed += locator.descriptor.value
            return if (ordinal < 11) collector.dismissProviderItem()
            else collector.accept(fixture.fact(request, "sample.Related.member$ordinal()", ordinal * 4))
        }

        fun resume(result: RelationCompilation.Qualified) {
            val continuation = (result.coverage as RelationIncompleteCoverage.Resumable).continuation
            assertEquals(consumed.size.toLong(), continuation.providerState.consumedLocatorCount.value)
            assertEquals(continuation.providerState.providerCursor, continuation.nextProviderCursor)
            request =
                RelationRequest.resume(
                        (initial.subject as RelationEndpoint.Subject).selector,
                        initial.meaning,
                        initial.budget,
                        continuation,
                    )
                    .value()
        }

        fun assertExhaustion(pages: Int) {
            assertEquals(121, facts.size)
            assertEquals(ranges.drop(11).toSet(), facts.map { it.occurrence.range }.toSet())
            assertEquals(descriptors, consumed)
            assertEquals((0L until 132L).toList(), visitedOrdinals)
            assertEquals(1, preparations)
            assertEquals(pages, observation.initialized)
            assertEquals(0, observation.replayedPrefix)
        }
    }

    private class Observation : IntellijReadObservation {
        var replayedPrefix = 0
        var initialized = 0

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            if (counter == IntellijReadCounter.RELATION_REPLAYED_PREFIX) {
                if (amount == 0) initialized += 1
                replayedPrefix += amount
            }
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    private fun <Value> Refinement<Value, *>.value(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Detached fixture rejected: $failure")
        }
}
