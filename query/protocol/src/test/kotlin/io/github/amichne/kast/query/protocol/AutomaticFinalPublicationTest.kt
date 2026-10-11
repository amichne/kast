package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.continuationToken
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticFinalPublicationTest : AutomaticSymbolQueryCase() {
    @Test
    fun `unavailable retained evidence still publishes advertised final progress`() = runTest {
        val checkpointBytes = request.copy(executionBudget = null).accountedRequestBytes() + 100L
        val store = QueryStateStore(maximumBytes = checkpointBytes + TRANSIENT_CLAIM_BASE_BYTES, clock = { 0L })
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store)
        val rejection =
            completionRejection(
                protocol.execute(
                    request,
                    fixture.authority,
                    budget,
                    policy(cancelled = { script.calls == 1 }),
                )
            )
        assertTrue(rejection.evidence is QueryCompletionEvidenceDocument.Unavailable)
        val token = originalCoverage(rejection).progress.continuationToken as QueryExecutionContinuation.Pipeline
        assertTrue(store.restoreCheckpoint(token, fixture.authority) is QueryCheckpointRestoration.Restored)
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertEquals(checkpointBytes, store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, script.calls)
    }

    @Test
    fun `prepared final progress stays private until publication commit`() = runTest {
        val prepared = Prepared()
        val rejection = prepared.run()
        val token = originalCoverage(rejection).progress.continuationToken as QueryExecutionContinuation.Pipeline
        val result = retainedEvidence(rejection).result
        assertEquals(QueryCheckpointRestoration.Unavailable, prepared.store.restoreCheckpoint(token, fixture.authority))
        assertEquals(QueryResultRestoration.Unavailable, prepared.store.restoreResult(result, fixture.authority))
        assertTrue(
            prepared.store.restoreCheckpoint(token, fixture.authority, prepared.claim)
                is QueryCheckpointRestoration.Restored
        )
        assertEquals(QueryPublicationCommit.Committed, prepared.store.commitPublication(prepared.claim, prepared.page))
        prepared.store.releasePublication(prepared.claim)
        assertTrue(prepared.store.restoreCheckpoint(token, fixture.authority) is QueryCheckpointRestoration.Restored)
        assertTrue(prepared.store.restoreResult(result, fixture.authority) is QueryResultRestoration.Restored)
        assertEquals(1, prepared.script.calls)
    }

    @Test
    fun `prepared final progress and rows are released on discard without renewing their authority`() = runTest {
        val prepared = Prepared()
        val rejection = prepared.run()
        val token = originalCoverage(rejection).progress.continuationToken as QueryExecutionContinuation.Pipeline
        val result = retainedEvidence(rejection).result
        prepared.store.releasePublication(prepared.claim)
        prepared.store.releasePublication(prepared.claim)
        assertEquals(QueryCheckpointRestoration.Unavailable, prepared.store.restoreCheckpoint(token, fixture.authority))
        assertEquals(QueryResultRestoration.Unavailable, prepared.store.restoreResult(result, fixture.authority))
        assertEquals(0L, prepared.store.retentionMeasurements().retainedBytes.value)
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE),
            prepared.store.commitPublication(prepared.claim, prepared.page),
        )
        assertEquals(1, prepared.script.calls)
    }

    @Test
    fun `final publication rejection rolls back only its checkpoint and result attempt`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), listOf(row)))
        var preparedCalls = 0
        val publication = QueryExecutionPublication { _, _, _ ->
            preparedCalls++
            QueryExecutionPublicationResult.Rejected(QueryPublicationFailure.CLAIM_UNAVAILABLE)
        }
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, publication)
        val rejected =
            protocol.execute(request, fixture.authority, budget, policy(cancelled = { script.calls == 1 }))
                as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_CLAIM_UNAVAILABLE),
            rejected.reason,
        )
        assertEquals(1, preparedCalls)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, script.calls)
    }

    @Test
    fun `invalid final projection releases transferred progress before publication`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), listOf(row)))
        val publication = QueryExecutionPublication { _, _, _ ->
            error("An invalid projection cannot prepare publication")
        }
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, publication)
        val rejected =
            protocol.execute(
                request,
                fixture.authority,
                budget,
                policy(
                    cancelled = { script.calls == 1 },
                    inlinePresentation = { QueryInlinePresentation.INVALID },
                ),
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION),
            rejected.reason,
        )
        assertEquals(0, store.retentionMeasurements().retainedEntries.value)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, script.calls)
    }

    private inner class Prepared {
        val store = QueryStateStore(clock = { 0L })
        val script = Script(listOf(listOf(row), listOf(row)))
        lateinit var claim: QueryExecutionClaim
        lateinit var page: QueryPublishedPage

        suspend fun run(): QueryRunRejection.CompletionUnproven {
            val publication = QueryExecutionPublication { _, owner, observed ->
                claim = owner
                page = observed
                QueryExecutionPublicationResult.PREPARED
            }
            val protocol = CanonicalQueryProtocol(script.operations, fixture.references, store, publication)
            return completionRejection(
                protocol.execute(
                    request,
                    fixture.authority,
                    budget,
                    policy(cancelled = { script.calls == 1 }),
                )
            )
        }
    }
}
