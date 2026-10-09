package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryRejectionTest : AutomaticSymbolQueryCase() {
    @Test
    fun `stale initial exact reference preserves manual rejection without semantic execution`() = runTest {
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    error("A stale exact reference must reject before semantic execution")
                },
                fixture.references,
            )
        val lease = SemanticReadLease(fixture.authority.workspaceRoot, EvidenceGeneration.parse(8).refined())
        val expected =
            OperationOutcome.Rejected(
                QueryRunRejection.ReferenceRejected(
                    ProtocolOffset.parse(0).refined(),
                    QueryReferenceRejectionReason.STALE_GENERATION,
                )
            )
        assertEquals(expected, protocol.executePage(request, lease, budget))

        assertEquals(expected, protocol.execute(request, lease, budget, policy()))

        assertEquals(0, calls)
    }

    @Test
    fun `unavailable retained input preserves manual rejection without inventing retained empty output`() = runTest {
        var calls = 0
        val state = QueryStateStore()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    error("An unavailable retained input must reject before semantic execution")
                },
                fixture.references,
                state,
            )
        val input =
            request.copy(
                from =
                    QueryFromDocument.Result(
                        QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
                    ),
                retention = QueryRetentionModeDocument.RETAIN,
            )
        val expected =
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE)
            )
        assertEquals(expected, protocol.executePage(input, fixture.authority, budget))

        assertEquals(expected, protocol.execute(input, fixture.authority, budget, policy()))

        assertEquals(0, calls)
        assertEquals(0L, state.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `initial semantic rejection remains rejected after exactly one execution`() = runTest {
        var calls = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    calls++
                    assertEquals(1, calls, "A rejected initial query must not replay semantic execution")
                    assertNull(it.checkpoint)
                    QueryExecutionResult.Rejected(QueryExecutionRejection.REFERENCE_STALE)
                },
                fixture.references,
            )

        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REFERENCE_STALE)
            ),
            protocol.execute(request, fixture.authority, budget, policy()),
        )

        assertEquals(1, calls)
    }

    @Test
    fun `rejection after an admitted empty page preserves accumulated partial coverage`() = runTest {
        val script = Script(listOf(emptyList(), listOf(row)), staleAfterFirst = true)
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)

        val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, policy()))
        assertEquals(0, retainedEvidence(rejection).preview.values.size)
        assertEquals(QueryInvocationStop.INVALID_STATE, rejection.stop)
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REFERENCE_STALE),
            rejection.originalFailure,
        )
        val read =
            protocol.executePage(retainedEvidence(rejection).readRequest(), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertEquals(0, read.evidence.payload.items.values.size)
        assertNull(read.qualification.progress.continuationToken)
        script.assertDrained()
    }
}
