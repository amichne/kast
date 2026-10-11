package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class AutomaticPrivateDependencyTest : AutomaticSymbolQueryCase() {
    @Test fun `final rollback preserves a private checkpoint referenced by public output`() = runTest { prove(false) }

    @Test
    fun `final rollback preserves a private checkpoint referenced by immutable public replay`() = runTest {
        prove(true)
    }

    @Test
    fun `final rollback preserves an active replay claim and its private checkpoint dependency`() = runTest {
        prove(true, replaying = true)
    }

    private suspend fun prove(consumed: Boolean, replaying: Boolean = false) {
        val store = QueryStateStore(clock = { 0L })
        val invocation = QueryInvocationPublication()
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, invocation)
        val initial = protocol.executePage(request, fixture.authority, budget) as OperationOutcome.Qualified
        val token = initial.qualification.progress.continuationToken as QueryExecutionContinuation.Pipeline
        val output = publishOutput(store, initial)
        if (consumed) {
            val producer =
                store.acquireOutput(output.token, fixture.authority, budget.returnedBytes.value)
                    as QueryOutputAcquisition.Acquired
            assertEquals(QueryPublicationCommit.Committed, store.commitPublication(producer.claim, initial))
            store.releasePublication(producer.claim)
        }
        val replayPin =
            if (replaying)
                store.acquireOutput(output.token, fixture.authority, budget.returnedBytes.value)
                    as QueryOutputAcquisition.Published
            else null
        val committedBytes = store.retentionMeasurements().retainedBytes
        val final = store.acquireInitial(fixture.authority) as QueryInitialAcquisition.Acquired
        store.finishInvocation(invocation, token, final.claim)
        store.releasePublication(final.claim)
        assertEquals(
            committedBytes,
            store.retentionMeasurements().retainedBytes,
            "Final rollback cannot delete a dependency of an independently committed output owner",
        )
        assertInstanceOf(
            QueryCheckpointRestoration.Restored::class.java,
            store.restoreCheckpoint(token, fixture.authority),
        )
        replayPin?.let { store.releasePublication(it.claim) }
        if (consumed) {
            val replay =
                store.acquireOutput(output.token, fixture.authority, budget.returnedBytes.value)
                    as QueryOutputAcquisition.Published
            assertEquals(initial, replay.page)
            store.releasePublication(replay.claim)
        } else {
            val producer =
                store.acquireOutput(output.token, fixture.authority, budget.returnedBytes.value)
                    as QueryOutputAcquisition.Acquired
            store.releasePublication(producer.claim)
        }
        assertEquals(1, script.calls)
        assertEquals(2, store.retentionMeasurements().retainedEntries.value)
    }

    private fun publishOutput(store: QueryStateStore, original: QueryPublishedPage): QueryOutputIssuance.Issued {
        val initial = original as OperationOutcome.Qualified
        val publicClaim = store.acquireInitial(fixture.authority) as QueryInitialAcquisition.Acquired
        val output =
            store.issueOutput(
                request,
                fixture.authority,
                initial,
                publicClaim.claim,
                (QueryRetentionByteCount.parse(512) as Refinement.Refined).value,
            ) as QueryOutputIssuance.Issued
        val page =
            OperationOutcome.Qualified(
                initial.evidence,
                (QueryRunQualification.create(
                        initial.qualification.knownMinimum,
                        initial.qualification.limitations,
                        QueryQualifiedProgressDocument.Resumable(
                            QueryCheckpointDocument.RetainedOutput(
                                output.token,
                                QueryPreparedCoverageDocument.Resumable,
                            ),
                            ReadResumeActionDocument.RESUME,
                        ),
                    ) as Refinement.Refined)
                    .value,
            )
        assertEquals(QueryPublicationCommit.Committed, store.commitPublication(publicClaim.claim, page))
        store.releasePublication(publicClaim.claim)
        return output
    }
}
