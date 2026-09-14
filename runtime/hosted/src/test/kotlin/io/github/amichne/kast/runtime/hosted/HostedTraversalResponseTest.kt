package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedTraversalResponseTest {
    @Test
    fun `oversized terminal traversal retains an ordered suffix and every partial expansion`() = runTest {
        val original = HostedTraversalPagingFixture.create().outcome
        val full =
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.traversalRun, original)
                as HostedResponse.Canonical<*, *, *>
        val maximum = ReturnedByteLimit.parse(full.document.toByteArray().size.toLong() - 1).proven()
        val retained = mutableListOf<HostedTraversalOutcome>()
        val response =
            encodeHostedTraversalResponse(original, ReadLimits.Default, ResultLimit.parse(3).proven(), maximum) {
                retained += it
                HostedOutputRetention.Retained(
                    ProtocolText.parse("traversal-output:v1:00000000-0000-0000-0000-000000000001").proven()
                )
            }
        assertTrue(response is HostedResponse.Canonical<*, *, *>, "Expected fitting traversal prefix, got $response")
        assertTrue(response.document.toByteArray().size <= maximum.value)
        val page =
            ((response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified).evidence.payload
                as TraversalRunResult
        val qualified =
            (response.semantic as OperationOutcome.Qualified).qualification as TraversalRunQualification.Resumable
        assertEquals(
            TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE,
            (qualified.checkpoint as TraversalCheckpointDocument.RetainedOutput).upstream,
        )
        val suffix = retained.single() as OperationOutcome.Qualified
        val remaining = suffix.evidence.payload as TraversalRunResult
        assertTrue(page.records.values.isNotEmpty())
        assertEquals(original.evidence.payload.records.values, page.records.values + remaining.records.values)
        assertEquals(original.evidence.payload.partialExpansions, page.partialExpansions)
        assertEquals(original.evidence.payload.partialExpansions, remaining.partialExpansions)
        assertEquals(original.evidence.payload.progress, page.progress)
        assertEquals(original.qualification, suffix.qualification)
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
