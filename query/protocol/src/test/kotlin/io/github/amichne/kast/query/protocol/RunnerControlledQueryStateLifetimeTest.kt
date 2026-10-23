package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Pure store lifetime tests: no compiler, filesystem, scheduler or native authority claim. */
class RunnerControlledQueryStateLifetimeTest : QueryStateLifetimeCase() {
    @Test
    fun `result presentation beyond quota does not admit ordinary semantic work`() = runTest {
        val store = QueryStateStore(capacity = 1, maximumBytes = 1)
        val issued = issueRunnerResult(store)
        val before = store.retentionMeasurements()
        val acquired =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(
                    lease,
                    setOf(issued.reference),
                    reservation = QueryInitialReservation.PRESENTATION,
                ),
            )
        assertInstanceOf(
            QueryResultRestoration.Restored::class.java,
            store.restoreResult(issued.reference, lease, acquired.claim),
        )
        assertEquals(QueryInitialAcquisition.CapacityExceeded, store.acquireInitial(lease, setOf(issued.reference)))
        assertEquals(
            QueryResultIssuance.CapacityExceeded,
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
        )
        store.releasePublication(acquired.claim)
        assertEquals(before.retainedEntries, store.retentionMeasurements().retainedEntries)
        assertEquals(before.retainedBytes, store.retentionMeasurements().retainedBytes)
    }

    @Test
    fun `result presentation preserves original result expiry and bounded claim lifetime`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 1, maximumBytes = 1, clock = { now }, ttlMillis = 10)
        val issued = issueRunnerResult(store)
        now = 5_000_000L
        val acquired =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(
                    lease,
                    setOf(issued.reference),
                    executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED,
                    reservation = QueryInitialReservation.PRESENTATION,
                ),
            )
        now = 11_000_000L
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
        val later =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(
                    lease,
                    setOf(issued.reference),
                    reservation = QueryInitialReservation.PRESENTATION,
                ),
            )
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease, later.claim))
        store.releasePublication(later.claim)
        assertEquals(
            QueryPublicationCommit.Rejected(QueryPublicationFailure.EXPIRED),
            store.commitPublication(acquired.claim, page(issued.reference)),
        )
        now = 16_000_000L
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease, acquired.claim))
        store.releasePublication(acquired.claim)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @TestFactory
    fun `result presentation beyond quota fails closed without semantic work`() =
        RejectedResultRead.entries.map { case ->
            val authority =
                if (case == RejectedResultRead.STALE_BASIS)
                    SemanticReadLease(lease.workspaceRoot, EvidenceGeneration.parse(8).refined())
                else lease
            dynamicTest(case.name) {
                runTest {
                    var now = 0L
                    var executions = 0
                    val store = QueryStateStore(capacity = 1, maximumBytes = 1, clock = { now }, ttlMillis = 10)
                    val issued = issueRunnerResult(store)
                    when (case) {
                        RejectedResultRead.EXPIRED -> now = 11_000_000L
                        RejectedResultRead.CLEARED -> store.clear()
                        RejectedResultRead.RETIRED -> store.retire()
                        RejectedResultRead.STALE_BASIS,
                        RejectedResultRead.UNKNOWN -> Unit
                    }
                    val protocol =
                        CanonicalQueryProtocol(
                            QueryOperations {
                                executions++
                                complete()
                            },
                            CanonicalQueryReferences(),
                            store,
                        )
                    val reference =
                        if (case == RejectedResultRead.UNKNOWN)
                            QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000002").refined()
                        else issued.reference
                    val read =
                        QueryRunRequest.ReadResult.symbols(
                            reference,
                            output = QueryOutputDocument.Symbols(bounded(emptyList())),
                        )
                    assertEquals(
                        OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(case.reason)),
                        protocol.executePage(read, authority, budget),
                    )
                    assertEquals(0, executions)
                }
            }
        }

    enum class RejectedResultRead(val reason: QueryExecutionRejectionDocument) {
        STALE_BASIS(QueryExecutionRejectionDocument.RESULT_STALE_BASIS),
        EXPIRED(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
        CLEARED(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
        RETIRED(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
        UNKNOWN(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
    }

    private fun issueRunnerResult(store: QueryStateStore): QueryResultIssuance.Issued =
        assertInstanceOf(
            QueryResultIssuance.Issued::class.java,
            store.issueResult(
                request().copy(executionMode = QueryExecutionModeDocument.RUNNER_CONTROLLED),
                QueryRetainedResult.capture(lease, complete()).refined(),
            ),
        )

    @Test
    fun `only the private runner owner can acquire its expired successor and release its pin`() = runTest {
        var now = 0L
        val store = QueryStateStore(capacity = 1, maximumBytes = 100_000L, clock = { now }, ttlMillis = 10)
        val invocation = QueryInvocationPublication()
        val token = issuePrivateCheckpoint(store, invocation)
        now = 20_000_000L
        assertEquals(1, store.retentionMeasurements().retainedEntries.value)
        assertEquals(
            QueryResultIssuance.CapacityExceeded,
            store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
        )
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.EXPIRED),
            store.acquireCheckpoint(token, lease, 1000),
        )
        assertEquals(
            QueryCheckpointAcquisition.Rejected(QueryContinuationFailure.EXPIRED),
            store.acquireCheckpoint(
                token = token,
                lease = lease,
                maximumPageBytes = 1000,
                invocation = QueryInvocationPublication(),
            ),
        )
        val acquired =
            assertInstanceOf(
                QueryCheckpointAcquisition.Acquired::class.java,
                store.acquireCheckpoint(
                    token = token,
                    lease = lease,
                    maximumPageBytes = 1000,
                    invocation = invocation,
                ),
            )
        store.releasePublication(acquired.claim)
        store.finishInvocation(invocation, null)
        assertEquals(0, store.retentionMeasurements().retainedEntries.value)
    }

    @Test
    fun `runner input claim keeps admitted evidence after TTL without renewing the external token`() = runTest {
        var now = 0L
        val store = QueryStateStore(clock = { now }, ttlMillis = 10)
        val issued =
            assertInstanceOf(
                QueryResultIssuance.Issued::class.java,
                store.issueResult(request(), QueryRetainedResult.capture(lease, complete()).refined()),
            )
        val acquired =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(
                    lease,
                    setOf(issued.reference),
                    io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED,
                ),
            )
        now = 20_000_000L
        assertInstanceOf(
            QueryResultRestoration.Restored::class.java,
            store.restoreResult(issued.reference, lease, acquired.claim),
        )
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
        store.releasePublication(acquired.claim)
        assertEquals(QueryResultRestoration.Unavailable, store.restoreResult(issued.reference, lease))
    }

    private suspend fun issuePrivateCheckpoint(
        store: QueryStateStore,
        invocation: QueryInvocationPublication,
    ): io.github.amichne.kast.protocol.contract.QueryExecutionContinuation.Pipeline {
        val runner =
            request()
                .copy(
                    executionMode =
                        io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument.RUNNER_CONTROLLED
                )
        val initial =
            assertInstanceOf(
                QueryInitialAcquisition.Acquired::class.java,
                store.acquireInitial(lease, executionMode = runner.executionMode),
            )
        val token =
            assertInstanceOf(
                    QueryCheckpointIssuance.Issued::class.java,
                    store.issueCheckpoint(runner, checkpoint(), publicationOwner = initial.claim),
                )
                .token
        assertEquals(
            QueryExecutionPublicationResult.COMMITTED,
            invocation.prepare(store, initial.claim, qualified(QueryCheckpointDocument.Upstream(token))),
        )
        store.releasePublication(initial.claim)
        return token
    }
}
