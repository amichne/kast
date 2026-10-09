package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production inventory orchestration with scripted native preparation; no claim about native search latency. */
class RelationInventoryObservationTest {
    @Test
    fun `each native inventory records its meaning and prepared or incomplete outcome`() {
        for ((meaning, contributor) in
            listOf(
                RelationMeaning.References to IntellijReadContributor.RELATION_REFERENCES,
                RelationMeaning.Callers to IntellijReadContributor.RELATION_CALLERS,
            )) {
            for (prepared in listOf(true, false)) {
                val observation = Counts()
                val request = RelationReadTest().request(meaning)
                val collector = IntellijRelationCollector(request, { 0L }, observation)
                val result =
                    readRelationInventory(
                        request,
                        collector,
                        {
                            if (prepared) {
                                val state = RelationProviderState.references(emptyList())
                                assertTrue(collector.retainProviderState(state, preparedPartition = true))
                                RelationInventoryPreparation.Prepared(state)
                            } else RelationInventoryPreparation.Unavailable
                        },
                        { _, _ -> error("Empty inventory must not confirm a locator") },
                        {},
                        observation,
                        { 0L },
                    )
                assertEquals(if (prepared) ProviderTermination.TERMINAL else ProviderTermination.HALTED, result)
                assertEquals(
                    1,
                    observation.values[IntellijReadCounter.NATIVE_RELATION_INVENTORIES_STARTED to contributor],
                )
                assertEquals(
                    1,
                    observation.values[
                            (if (prepared) IntellijReadCounter.NATIVE_RELATION_INVENTORIES_PREPARED
                            else IntellijReadCounter.NATIVE_RELATION_INVENTORIES_INCOMPLETE) to contributor],
                )
                assertEquals(
                    null,
                    observation.values[IntellijReadCounter.NATIVE_RELATION_INVENTORIES_INTERRUPTED to contributor],
                )
            }
        }
    }

    @Test
    fun `native cancellation preserves an interrupted signal and propagates`() {
        val observation = Counts()
        val request = RelationReadTest().request(RelationMeaning.Callers)
        val collector = IntellijRelationCollector(request, { 0L }, observation)
        assertThrows(CancellationException::class.java) {
            readRelationInventory(
                request,
                collector,
                { throw CancellationException("scripted native cancellation") },
                { _, _ -> error("Cancelled inventory cannot confirm") },
                {},
                observation,
                { 0L },
            )
        }
        assertEquals(
            1,
            observation.values[
                    IntellijReadCounter.NATIVE_RELATION_INVENTORIES_INTERRUPTED to
                        IntellijReadContributor.RELATION_CALLERS],
        )
        assertEquals(
            null,
            observation.values[
                    IntellijReadCounter.NATIVE_RELATION_INVENTORIES_PREPARED to
                        IntellijReadContributor.RELATION_CALLERS],
        )
    }

    @Test
    fun `reference and caller inventory timings have separate finite phases`() {
        assertEquals(IntellijReadPhase.REFERENCE_INVENTORY, referenceInventoryPhase(RelationMeaning.References))
        assertEquals(IntellijReadPhase.CALLER_REFERENCE_INVENTORY, referenceInventoryPhase(RelationMeaning.Callers))
        assertEquals(IntellijReadPhase.TYPE_USE_REFERENCE_INVENTORY, referenceInventoryPhase(RelationMeaning.TypeUses))
    }

    private class Counts : IntellijReadObservation {
        val values = mutableMapOf<Pair<IntellijReadCounter, IntellijReadContributor>, Int>()

        override fun terminated(
            reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
            contributor: IntellijReadContributor,
        ) = Unit

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            val key = counter to contributor
            values[key] = values.getOrDefault(key, 0) + amount
        }
    }
}
