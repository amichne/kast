package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The real preparation/ordinal rule with a scripted native inventory and independent confirmation effects. */
class ReferenceInventoryReuseTest {
    private val original = RelationReadTest().request(RelationMeaning.References)
    private val state =
        RelationProviderState.references(
            listOf(
                RelationProviderLocator.Reference(
                    original.subject.file,
                    ExactDeclarationTextRange.parse(100, 101).refined(),
                    RelationProviderItemDescriptor.parse("native-reference").refined(),
                )
            )
        )

    @Test
    fun `references and callers share one prepared inventory but confirm independently`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        val observation = Counts()
        var preparations = 0
        var confirmations = 0
        val callers =
            RelationRequest.start(original.subject, RelationMeaning.Callers, original.budget, original.boundary)
        for (request in listOf(original, callers)) {
            val collector = IntellijRelationCollector(request, { 0L }, observation)
            assertEquals(
                ProviderTermination.TERMINAL,
                readRelationInventory(
                    request,
                    collector,
                    {
                        assertEquals(1, ++preparations, "CALLERS must not repeat the completed native search")
                        assertTrue(collector.retainProviderState(state, preparedPartition = true))
                        RelationInventoryPreparation.Prepared(state)
                    },
                    { _, locator ->
                        assertEquals(100, locator.range.startInclusive)
                        confirmations++
                        collector.dismissProviderItem()
                    },
                    {},
                    observation,
                    { 0L },
                    reuse,
                ),
            )
        }
        assertEquals(1, preparations)
        assertEquals(2, confirmations)
        assertEquals(
            1,
            observation.values[
                    IntellijReadCounter.NATIVE_RELATION_INVENTORIES_STARTED to
                        IntellijReadContributor.RELATION_REFERENCES],
        )
        assertEquals(
            1,
            observation.values[
                    IntellijReadCounter.RELATION_INVENTORIES_REUSED to IntellijReadContributor.RELATION_CALLERS],
        )
        assertEquals(0L, state.consumedLocatorCount.value, "Consumption must not mutate the shared initial ordinal")
    }

    @Test
    fun `reuse cannot broaden the requested domain or cross invocation owners`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        reuse.remember(original, state)
        val workspace =
            RelationRequest.start(
                original.subject,
                RelationMeaning.Callers,
                original.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        assertEquals(ReferenceInventoryLookup.Miss, reuse.find(workspace, IntellijRelationCollector(workspace, { 0L })))
        assertEquals(
            ReferenceInventoryLookup.Miss,
            IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
                .find(original, IntellijRelationCollector(original, { 0L })),
        )
        assertInstanceOf(
            ReferenceInventoryLookup.Found::class.java,
            reuse.find(original, IntellijRelationCollector(original, { 0L })),
        )
    }

    @Test
    fun `one slot replaces its previous domain and observes its byte ceiling`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        val workspace =
            RelationRequest.start(
                original.subject,
                RelationMeaning.Callers,
                original.budget,
                RelationSearchBoundary.WORKSPACE_EXPANSION,
            )
        reuse.remember(original, state)
        reuse.remember(workspace, state)
        assertEquals(ReferenceInventoryLookup.Miss, reuse.find(original, IntellijRelationCollector(original, { 0L })))
        val small = ReadLimits.resolve(mapOf(ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "1")).refined()
        val bounded = IntellijReferenceInventoryReuse.Recent(small)
        assertEquals(ReferenceInventoryRetention.CAPACITY_EXCEEDED, bounded.remember(original, state))
        assertEquals(ReferenceInventoryLookup.Miss, bounded.find(original, IntellijRelationCollector(original, { 0L })))
    }

    @Test
    fun `reuse rejects a moved authority even when declaration facts and scope match`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        reuse.remember(original, state)
        val published = original.subject.lease as SemanticReadLease
        val current = published.copy(generation = EvidenceGeneration.parse(20L).refined())
        val previous = (original.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
        val selector =
            SymbolSelector.issue(
                current,
                previous.scope,
                CompilerGroundedSymbolEvidence.fromSelector(previous),
                previous.constraints,
            )
        val moved = RelationRequest.start(selector, RelationMeaning.Callers, original.budget, original.boundary)
        assertEquals(ReferenceInventoryLookup.Miss, reuse.find(moved, IntellijRelationCollector(moved, { 0L })))
    }

    @Test
    fun `reuse rejection preserves the receiving collector retention boundary`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        reuse.remember(original, state)
        val observation = Counts()
        val small = ReadLimits.resolve(mapOf(ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "1")).refined()
        val collector = IntellijRelationCollector(original, { 0L }, observation, small)
        assertEquals(
            ProviderTermination.HALTED,
            readRelationInventory(
                original,
                collector,
                { error("Rejected reuse must not retry native preparation") },
                { _, _ -> error("Rejected reuse cannot confirm") },
                {},
                observation,
                { 0L },
                reuse,
            ),
        )
        assertEquals(
            1,
            observation.values[
                    IntellijReadCounter.RELATION_INVENTORY_REUSE_REJECTED to
                        IntellijReadContributor.RELATION_REFERENCES],
        )
        assertEquals(
            1,
            observation.values[IntellijReadCounter.RELATION_INVENTORY_UNAVAILABLE to IntellijReadContributor.NONE],
        )
    }

    @Test
    fun `cached inventory cannot bypass an exhausted elapsed grant`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        reuse.remember(original, state)
        var now = 0L
        val collector = IntellijRelationCollector(original, { now })
        now = original.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertEquals(
            ProviderTermination.HALTED,
            readRelationInventory(
                original,
                collector,
                { error("Exhausted grant cannot prepare") },
                { _, _ -> error("Exhausted grant cannot confirm") },
                {},
                inventories = reuse,
            ),
        )
    }

    @Test
    fun `incomplete preparation never becomes a reusable inventory`() {
        val reuse = IntellijReferenceInventoryReuse.Recent(ReadLimits.Default)
        val collector = IntellijRelationCollector(original, { 0L })
        assertEquals(
            ProviderTermination.HALTED,
            readRelationInventory(
                original,
                collector,
                { RelationInventoryPreparation.Unavailable },
                { _, _ -> error("Incomplete inventory cannot confirm") },
                {},
                inventories = reuse,
            ),
        )
        assertEquals(ReferenceInventoryLookup.Miss, reuse.find(original, IntellijRelationCollector(original, { 0L })))
    }

    private class Counts : IntellijReadObservation {
        val values = mutableMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Int>()

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            val key = counter to contributor
            values[key] = values.getOrDefault(key, 0) + amount
        }
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
