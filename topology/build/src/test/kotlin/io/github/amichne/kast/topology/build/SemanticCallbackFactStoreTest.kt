package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SemanticCallbackFactStoreTest : SemanticCallbackStoreFixture() {
    @Test
    fun `separate lookup reuses exact snapshot while moved snapshot requires readmission`() {
        val prior = snapshot(owner.admit())
        val summary = summary(prior.authority)
        assertEquals(SemanticCallbackPublication.Published, store.publish(prior, summary))
        assertInstanceOf(SemanticCallbackLookup.Current::class.java, store.find(prior, summary.formal))
        val next = snapshot(owner.advance())
        val reused =
            assertInstanceOf(
                SemanticCallbackLookup.Reusable::class.java,
                store.find(next, summary(next.authority).formal),
            )
        assertEquals(summary, reused.previous)
        assertEquals(next, reused.proof.current)
        assertEquals(prior.authority, reused.previous.formal.callable.lease)
    }

    @Test
    fun `changed dependency invalidates entry and never yields old fact`() {
        val prior = snapshot(owner.admit())
        store.publish(prior, summary(prior.authority))
        val next = snapshot(owner.advance(), 'b')
        assertInstanceOf(
            SemanticCallbackLookup.Invalidated::class.java,
            store.find(next, summary(next.authority).formal),
        )
        assertEquals(SemanticCallbackLookup.Missing, store.find(next, summary(next.authority).formal))
    }

    @Test
    fun `late publication and retired store cannot accept facts`() {
        val prior = snapshot(owner.admit())
        val next = snapshot(owner.advance())
        assertInstanceOf(
            SemanticCallbackPublication.Rejected::class.java,
            store.publish(prior, summary(prior.authority)),
        )
        assertInstanceOf(
            SemanticCallbackPublication.Rejected::class.java,
            store.publish(next, summary(prior.authority)),
        )
        store.retire()
        assertInstanceOf(SemanticCallbackPublication.Rejected::class.java, store.publish(next, summary(next.authority)))
        assertInstanceOf(SemanticCallbackLookup.Rejected::class.java, store.find(next, summary(next.authority).formal))
    }

    @Test
    fun `source absent from complete inventory cannot publish a summary`() {
        val snapshot = snapshot(owner.admit(), includeSource = false)
        assertEquals(
            SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SourceOutsideInventory),
            store.publish(snapshot, summary(snapshot.authority)),
        )
        assertEquals(SemanticCallbackLookup.Missing, store.find(snapshot, summary(snapshot.authority).formal))
    }

    @Test
    fun `independent semantic owner cannot read or publish project facts`() {
        val prior = snapshot(owner.admit())
        assertEquals(SemanticCallbackPublication.Published, store.publish(prior, summary(prior.authority)))
        val foreign = snapshot(MovingLiveReadAuthorityFixture(root).admit())
        assertInstanceOf(
            SemanticCallbackPublication.Rejected::class.java,
            store.publish(foreign, summary(foreign.authority)),
        )
        assertInstanceOf(
            SemanticCallbackLookup.Rejected::class.java,
            store.find(foreign, summary(foreign.authority).formal),
        )
        assertInstanceOf(
            SemanticCallbackLookup.Current::class.java,
            store.find(prior, summary(prior.authority).formal),
        )
    }

    @Test
    fun `concurrent publications preserve entry capacity and existing admitted fact`() {
        val bounded =
            SemanticCallbackFactStore(
                ReadLimits.resolve(properties = mapOf(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.propertyKey to "1"))
                    .value()
            )
        val snapshot = snapshot(owner.admit())
        val candidates = listOf(summary(snapshot.authority), summary(snapshot.authority, 30))
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = candidates.map { candidate ->
                executor.submit<SemanticCallbackPublication> {
                    barrier.await(10, TimeUnit.SECONDS)
                    bounded.publish(snapshot, candidate)
                }
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == SemanticCallbackPublication.Published })
            assertEquals(1, results.count { it == SemanticCallbackPublication.CapacityExceeded })
            candidates.zip(results).forEach { (candidate, result) ->
                when (result) {
                    SemanticCallbackPublication.Published -> {
                        assertInstanceOf(
                            SemanticCallbackLookup.Current::class.java,
                            bounded.find(snapshot, candidate.formal),
                        )
                        assertEquals(SemanticCallbackPublication.Published, bounded.publish(snapshot, candidate))
                    }
                    SemanticCallbackPublication.CapacityExceeded ->
                        assertEquals(SemanticCallbackLookup.Missing, bounded.find(snapshot, candidate.formal))
                    is SemanticCallbackPublication.Rejected -> error("unexpected publication rejection")
                }
            }
        }
    }

    @Test
    fun `snapshot retention consumes byte capacity before publication`() {
        val bounded =
            SemanticCallbackFactStore(
                ReadLimits.resolve(
                        properties =
                            mapOf(
                                ReadLimitParameter.QUERY_CONTINUATION_BYTES.propertyKey to "1",
                                ReadLimitParameter.QUERY_CHECKPOINT_BYTES.propertyKey to "1",
                            )
                    )
                    .value()
            )
        val snapshot = snapshot(owner.admit())
        val summary = summary(snapshot.authority)
        assertEquals(SemanticCallbackPublication.CapacityExceeded, bounded.publish(snapshot, summary))
        assertEquals(SemanticCallbackLookup.Missing, bounded.find(snapshot, summary.formal))
    }
}
