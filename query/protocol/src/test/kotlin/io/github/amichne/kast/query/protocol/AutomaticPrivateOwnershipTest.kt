package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticPrivateOwnershipTest : AutomaticSymbolQueryCase() {
    @Test
    fun `reused public checkpoint exposes a branching replay chain that survives private cleanup`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val pages = listOf(listOf(row), emptyList(), listOf(row), listOf(row))
        val publicScript = Script(pages)
        val publicProtocol = CanonicalQueryProtocol(publicScript.operations, fixture.references, store)
        val initial = publicProtocol.executePage(request, fixture.authority, budget) as OperationOutcome.Qualified
        val token = requireNotNull(initial.qualification.progress.continuationToken)
        val existing =
            (store.restoreCheckpoint(token as QueryExecutionContinuation.Pipeline, fixture.authority)
                    as QueryCheckpointRestoration.Restored)
                .checkpoint
        val automatic = Script(pages)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reuse =
            reuseFirst(automatic, existing) {
                if (automatic.calls == 3) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        val protocol = CanonicalQueryProtocol(reuse, fixture.references, store)
        val running = async { protocol.execute(request, fixture.authority, budget, policy()) }
        entered.await()
        val publicTokens = branchingWhileBusy(publicProtocol, token)
        release.complete(Unit)
        assertTrue(running.await() is OperationOutcome.Complete)
        val beforeReplay = store.retentionMeasurements().retainedBytes
        val replayed = publicTokens.map { replay(publicProtocol, it) }
        assertEquals(
            listOf(0, 1, 1),
            replayed.map { page ->
                when (page) {
                    is OperationOutcome.Complete -> page.evidence.payload.items.values.size
                    is OperationOutcome.Qualified -> page.evidence.payload.items.values.size
                    is OperationOutcome.Rejected -> error("Unexpected public replay rejection")
                }
            },
        )
        publicTokens.zip(replayed).forEach { (ref, page) -> assertEquals(page, replay(publicProtocol, ref)) }
        assertEquals(1, publicScript.calls, "The public replay must perform no semantic work")
        assertEquals(3, store.retentionMeasurements().retainedEntries.value)
        assertEquals(beforeReplay, store.retentionMeasurements().retainedBytes)
        automatic.assertDrained()
    }

    @Test
    fun `private checkpoint cannot be reissued publicly under the same semantic identity`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val invocation = QueryInvocationPublication()
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, invocation)
        val initial = protocol.executePage(request, fixture.authority, budget) as OperationOutcome.Qualified
        val privateToken = initial.qualification.progress.continuationToken as QueryExecutionContinuation.Pipeline
        val checkpoint =
            (store.restoreCheckpoint(privateToken, fixture.authority) as QueryCheckpointRestoration.Restored).checkpoint
        assertEquals(QueryCheckpointIssuance.Unavailable, store.retainedCheckpoint(request, checkpoint))
        val readyBytes = store.retentionMeasurements().retainedBytes
        val other = Script(listOf(listOf(row), emptyList(), listOf(row)))
        val equalInput = reuseFirst(other, checkpoint)
        assertTrue(
            CanonicalQueryProtocol(equalInput, fixture.references, store)
                .execute(request, fixture.authority, budget, policy()) is OperationOutcome.Complete
        )
        assertEquals(
            readyBytes,
            store.retentionMeasurements().retainedBytes,
            "Equal input in a second invocation cannot consume or publish the first private allocation",
        )
        assertTrue(store.restoreCheckpoint(privateToken, fixture.authority) is QueryCheckpointRestoration.Restored)
        other.assertDrained()
        val publicToken = (store.issueCheckpoint(request, checkpoint) as QueryCheckpointIssuance.Issued).token
        assertNotEquals(privateToken, publicToken)
        val ownedBytes = store.retentionMeasurements().retainedBytes
        store.finishInvocation(QueryInvocationPublication(), null)
        assertEquals(
            ownedBytes,
            store.retentionMeasurements().retainedBytes,
            "A different identity cannot release this owner",
        )
        val second =
            protocol.executePage(QueryRunRequest.Resume(privateToken), fixture.authority, budget)
                as OperationOutcome.Qualified
        val next = requireNotNull(second.qualification.progress.continuationToken)
        assertTrue(
            protocol.executePage(QueryRunRequest.Resume(next), fixture.authority, budget) is OperationOutcome.Complete
        )
        store.finishInvocation(invocation, null)
        store.finishInvocation(invocation, null)
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertEquals(
            checkpoint,
            (store.restoreCheckpoint(publicToken, fixture.authority) as QueryCheckpointRestoration.Restored).checkpoint,
        )
        assertEquals(QueryCheckpointRestoration.Unavailable, store.restoreCheckpoint(privateToken, fixture.authority))
        script.assertDrained()
    }

    private fun reuseFirst(
        script: Script,
        checkpoint: QueryCheckpoint,
        beforePage: suspend () -> Unit = {},
    ) = QueryOperations { execution ->
        beforePage()
        val observed = script.operations.run(execution)
        if (script.calls == 1)
            (observed as QueryExecutionResult.Qualified).copy(
                continuation = QueryContinuationState.Resumable(checkpoint)
            )
        else observed
    }

    private suspend fun replay(protocol: CanonicalQueryProtocol, token: QueryExecutionContinuation) =
        protocol.executePage(QueryRunRequest.Resume(token), fixture.authority, budget)

    private suspend fun branchingWhileBusy(
        protocol: CanonicalQueryProtocol,
        token: QueryExecutionContinuation,
    ): List<QueryExecutionContinuation> {
        val first = replay(protocol, token) as OperationOutcome.Qualified
        val child = requireNotNull(first.qualification.progress.continuationToken)
        val second = replay(protocol, child) as OperationOutcome.Qualified
        val grandchild = requireNotNull(second.qualification.progress.continuationToken)
        assertEquals(first, replay(protocol, token))
        assertEquals(second, replay(protocol, child))
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_IN_USE),
            (replay(protocol, grandchild) as OperationOutcome.Rejected).reason,
        )
        return listOf(token, child, grandchild)
    }
}
