package io.github.amichne.kast.topology.build

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticCallbackStoreRetirementTest : SemanticCallbackStoreFixture() {
    @Test
    fun `retirement rejects publication from an already admitted concurrent owner operation`() {
        val snapshot = snapshot(owner.admit())
        val summary = summary(snapshot.authority)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val pending =
                executor.submit<SemanticCallbackPublication> {
                    snapshot.authority
                        .withCurrentOwner {
                            entered.countDown()
                            assertTrue(resume.await(10, TimeUnit.SECONDS))
                            store.publish(snapshot, summary)
                        }
                        .value()
                }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                store.retire()
            } finally {
                resume.countDown()
            }
            assertEquals(
                SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.Retired),
                pending.get(10, TimeUnit.SECONDS),
            )
        }
        assertEquals(
            SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.Retired),
            store.find(snapshot, summary.formal),
        )
    }

    @Test
    fun `completed concurrent publication cannot survive later terminal retirement`() {
        val snapshot = snapshot(owner.admit())
        val summary = summary(snapshot.authority)
        Executors.newSingleThreadExecutor().use { executor ->
            assertEquals(
                SemanticCallbackPublication.Published,
                executor
                    .submit<SemanticCallbackPublication> { store.publish(snapshot, summary) }
                    .get(10, TimeUnit.SECONDS),
            )
            store.retire()
            assertEquals(
                SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.Retired),
                store.find(snapshot, summary.formal),
            )
        }
    }
}
