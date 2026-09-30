package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationPublishedSnapshotIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production preparation/accounting rules from detached sites; no native search or K2 facts are asserted. */
class RelationInventoryPreparationTest {
    private val fixture = RelationReadTest()
    private val request = fixture.request(RelationMeaning.References)

    @Test
    fun `a published snapshot inventory cannot enter native confirmation`() {
        val lease = (request.subject.lease.requirePublished() as Refinement.Refined).value
        val publication = (RelationPublishedSnapshotIdentity.admit(lease, "a".repeat(64)) as Refinement.Refined).value
        val retained =
            (RelationProviderState.publishedFacts(publication, listOf(fixture.fact(request))) as Refinement.Refined)
                .value
        val collector = IntellijRelationCollector(request, { 0L })
        val termination =
            readRelationInventory(
                request,
                collector,
                prepare = { RelationInventoryPreparation.Prepared(retained) },
                confirm = { _, _ -> error("Published evidence must not be presented as fresh native confirmation") },
                cancellationCheck = {},
            )
        assertEquals(ProviderTermination.HALTED, termination)
        val result = terminal(collector)
        assertEquals(setOf(RelationLimitation.UNSUPPORTED_ITEM), result.coverage.limitations)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }

    @Test
    fun `candidate cap halts detachment and cannot publish a partial inventory as resumable work`() {
        val observation = Observation()
        val limits = limits(ReadLimitParameter.RELATION_CANDIDATES, 2)
        val collector = IntellijRelationCollector(request, { 0L }, observation, limits)
        val inventory = IntellijRelationInventory<RelationProviderLocator.Reference>(collector, limits, observation)
        var detached = 0
        val exhausted =
            (0 until 3).all { ordinal ->
                if (collector.admitProviderCandidate() != IntellijRelationProviderEnumerationAdmission.READY) false
                else {
                    detached += 1
                    inventory.append(Refinement.Refined(locator(ordinal)))
                }
            }
        assertFalse(exhausted)
        assertEquals(2, detached)
        assertEquals(
            RelationInventoryPreparation.Unavailable,
            inventory.finish(exhausted, RelationProviderState::references),
        )
        val result = terminal(collector)
        assertTrue(RelationLimitation.CANDIDATE_LIMIT_REACHED in result.coverage.limitations)
        assertTrue(RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE in result.coverage.limitations)
        assertEquals(2, observation.candidates)
        assertEquals(0, observation.prepared)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }

    @Test
    fun `retention admission stops before buffering a locator that cannot fit`() {
        val observation = Observation()
        val first = locator(0)
        val limits = limits(ReadLimitParameter.QUERY_CHECKPOINT_BYTES, (512L + first.retainedBytes - 1L).toInt())
        val collector = IntellijRelationCollector(request, { 0L }, observation, limits)
        val inventory = IntellijRelationInventory<RelationProviderLocator.Reference>(collector, limits, observation)
        assertFalse(inventory.append(Refinement.Refined(first)))
        assertEquals(
            RelationInventoryPreparation.Unavailable,
            inventory.finish(false, RelationProviderState::references),
        )
        val result = terminal(collector)
        assertTrue(RelationLimitation.RETENTION_LIMIT_REACHED in result.coverage.limitations)
        assertEquals(0, observation.prepared)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }

    @Test
    fun `deadline after native exhaustion cannot admit an unfinished inventory`() {
        val observation = Observation()
        var now = 0L
        val collector = IntellijRelationCollector(request, { now }, observation)
        val inventory =
            IntellijRelationInventory<RelationProviderLocator.Reference>(collector, ReadLimits.Default, observation)
        assertTrue(inventory.append(Refinement.Refined(locator(0))))
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertEquals(
            RelationInventoryPreparation.Unavailable,
            inventory.finish(true, RelationProviderState::references),
        )
        val result = terminal(collector)
        assertTrue(RelationLimitation.TIME_LIMIT_REACHED in result.coverage.limitations)
        assertEquals(0, observation.prepared)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }

    @Test
    fun `complete bounded preparation preserves unfinished work without claiming semantic results`() {
        val observation = Observation()
        var now = 0L
        val collector = IntellijRelationCollector(request, { now }, observation)
        val inventory =
            IntellijRelationInventory<RelationProviderLocator.Reference>(collector, ReadLimits.Default, observation)
        for (ordinal in 0 until 2) {
            assertEquals(IntellijRelationProviderEnumerationAdmission.READY, collector.admitProviderCandidate())
            assertTrue(inventory.append(Refinement.Refined(locator(ordinal))))
        }
        val prepared =
            assertInstanceOf(
                RelationInventoryPreparation.Prepared::class.java,
                inventory.finish(true, RelationProviderState::references),
            )
        assertEquals(0L, prepared.state.consumedLocatorCount.value)
        assertEquals(1L, prepared.state.providerCursor.nextPosition.value)
        assertEquals(listOf(locator(0), locator(1)), prepared.state.prepared)
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertEquals(IntellijRelationProviderItemAdmission.HALTED, collector.beginProviderItem(locator(0).descriptor))
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                collector.finish(IntellijRelationTermination.Resumable(emptySet())),
            )
        val continuation =
            assertInstanceOf(RelationIncompleteCoverage.Resumable::class.java, result.coverage).continuation
        assertEquals(prepared.state.providerCursor, continuation.nextProviderCursor)
        assertTrue(continuation.providerState.hasUnfinishedWork)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
        assertEquals(0, result.batch.resultCount.value)
        assertEquals(2, observation.candidates)
        assertEquals(1, observation.prepared)
    }

    private fun terminal(collector: IntellijRelationCollector): RelationCompilation.Qualified {
        val result =
            assertInstanceOf(
                RelationCompilation.Qualified::class.java,
                collector.finish(IntellijRelationTermination.Resumable(emptySet())),
            )
        assertInstanceOf(RelationIncompleteCoverage.TerminalIncomplete::class.java, result.coverage)
        assertTrue(result.batch.facts.isEmpty())
        assertTrue(result.batch.referenceOccurrences.isEmpty())
        return result
    }

    private fun locator(ordinal: Int) =
        RelationProviderLocator.Reference(
            request.subject.file,
            exactFixtureRange(ordinal * 2, ordinal * 2 + 1),
            fixture.providerItem("reference:$ordinal"),
        )

    private fun limits(parameter: ReadLimitParameter, value: Int): ReadLimits =
        when (val admitted = ReadLimits.resolve(mapOf(parameter.environmentKey to value.toString()))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Inventory fixture limits rejected: ${admitted.failure}")
        }

    private class Observation : IntellijReadObservation {
        var candidates = 0
        var prepared = 0

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            when (counter) {
                IntellijReadCounter.RELATION_CANDIDATES -> candidates += amount
                IntellijReadCounter.RELATION_PARTITIONS_PREPARED -> prepared += amount
                else -> Unit
            }
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }
}
