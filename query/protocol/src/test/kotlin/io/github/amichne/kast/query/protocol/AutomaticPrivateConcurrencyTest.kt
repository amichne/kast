package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticPrivateConcurrencyTest : AutomaticSymbolQueryCase() {
    @Test
    fun `final transfer cannot steal a checkpoint pinned by another producer`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val invocation = QueryInvocationPublication()
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, invocation)
        val initial = protocol.executePage(request, fixture.authority, budget) as OperationOutcome.Qualified
        val token = initial.qualification.progress.continuationToken as QueryExecutionContinuation.Pipeline
        val ready = store.retentionMeasurements().retainedBytes
        val peer =
            store.acquireCheckpoint(token, fixture.authority, budget.returnedBytes.value)
                as QueryCheckpointAcquisition.Acquired
        val running = store.retentionMeasurements().retainedBytes
        val final = store.acquireInitial(fixture.authority) as QueryInitialAcquisition.Acquired
        store.finishInvocation(invocation, token, final.claim)
        store.releasePublication(final.claim)
        assertEquals(
            running,
            store.retentionMeasurements().retainedBytes,
            "Final rollback cannot remove the peer's Running allocation or reservation",
        )
        assertEquals(
            QueryCheckpointAcquisition.InUse,
            store.acquireCheckpoint(token, fixture.authority, budget.returnedBytes.value),
        )
        store.releasePublication(peer.claim)
        assertEquals(ready, store.retentionMeasurements().retainedBytes)
        assertTrue(store.restoreCheckpoint(token, fixture.authority) is QueryCheckpointRestoration.Restored)
        val completed = protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget)
        assertTrue(completed is OperationOutcome.Complete)
        assertEquals(completed, protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget))
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        script.assertDrained()
    }

    @Test
    fun `interleaved invocation cleanup preserves another running owner and its reservation`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstScript = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val paused = QueryOperations { execution ->
            if (firstScript.calls == 1) {
                entered.complete(Unit)
                release.await()
            }
            firstScript.operations.run(execution)
        }
        val firstProtocol = CanonicalQueryProtocol(paused, fixture.references, store)
        val first = async { firstProtocol.execute(request, fixture.authority, budget, policy()) }
        entered.await()
        val running = store.retentionMeasurements().retainedBytes
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertTrue(running.value > budget.returnedBytes.value * QUERY_PAGE_RESERVATION_MULTIPLIER)
        val other = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val otherProtocol = CanonicalQueryProtocol(other.operations, fixture.references, store)
        assertTrue(otherProtocol.execute(request, fixture.authority, budget, policy()) is OperationOutcome.Complete)
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertEquals(
            running,
            store.retentionMeasurements().retainedBytes,
            "The suspended owner's reservation stays intact",
        )
        release.complete(Unit)
        assertTrue(first.await() is OperationOutcome.Complete)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        firstScript.assertDrained()
        other.assertDrained()
    }

    @Test
    fun `retirement during a private producer revokes its reservation and prevents resurrection`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val script = Script(listOf(listOf(row), listOf(row), listOf(row)))
        val paused = QueryOperations { execution ->
            if (script.calls == 1) {
                entered.complete(Unit)
                release.await()
            }
            script.operations.run(execution)
        }
        val protocol = CanonicalQueryProtocol(paused, fixture.references, store)
        val running = async { protocol.execute(request, fixture.authority, budget, policy()) }
        entered.await()
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertTrue(store.retentionMeasurements().retainedBytes.value > 0)
        store.retire()
        release.complete(Unit)
        val rejected = running.await() as OperationOutcome.Rejected
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE
            ),
            rejected.reason,
        )
        assertEquals(2, script.calls, "Retirement cannot start another producer")
        assertEquals(0, store.retentionMeasurements().retainedEntries.value)
        assertEquals(REVOCATION_RECEIPT_BYTES, store.retentionMeasurements().retainedBytes.value)
    }
}
