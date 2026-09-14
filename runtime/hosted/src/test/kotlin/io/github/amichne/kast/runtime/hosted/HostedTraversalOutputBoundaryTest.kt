package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne/kast/kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AR-02: distinguish result fitting from byte fitting and failed checkpoint publication. */
class HostedTraversalOutputBoundaryTest {
    @Test
    fun `result only fitting preserves terminal coverage and ordered graph evidence`() = runTest {
        val original = HostedTraversalPagingFixture.create().outcome
        val maximumBytes = defaultByteLimit()
        assertTrue(original.evidence.payload.records.values.size > 1)
        assertTrue(
            HostedResponse.Canonical.encode(
                CanonicalOperationWireBindings.traversalRun,
                original,
                ReadLimits.Default,
                maximumBytes,
            ) is HostedResponse.Canonical<*, *, *>
        )
        val retained = mutableListOf<HostedTraversalOutcome>()
        val response =
            encodeHostedTraversalResponse(original, ReadLimits.Default, ResultLimit.parse(1).proven(), maximumBytes) {
                retained += it
                HostedOutputRetention.Retained(ProtocolText.parse(OUTPUT_TOKEN).proven())
            } as HostedResponse.Canonical<*, *, *>
        val semantic = response.semantic as OperationOutcome.Qualified
        val page = semantic.evidence.payload as TraversalRunResult
        val qualification = semantic.qualification as TraversalRunQualification.Resumable
        val suffix = retained.single() as OperationOutcome.Qualified
        val remaining = suffix.evidence.payload as TraversalRunResult

        assertEquals(1, page.records.values.size)
        assertEquals(original.evidence.payload.records.values, page.records.values + remaining.records.values)
        assertEquals(original.evidence.payload.partialExpansions, page.partialExpansions)
        assertEquals(original.evidence.payload.partialExpansions, remaining.partialExpansions)
        assertEquals(original.evidence.payload.progress, page.progress)
        assertEquals(original.evidence.payload.progress, remaining.progress)
        assertEquals(original.qualification, suffix.qualification)
        assertTrue(TraversalLimitationDocument.RECORD_LIMIT_REACHED in qualification.limitations)
        assertFalse(TraversalLimitationDocument.BYTE_LIMIT_REACHED in qualification.limitations)
        assertEquals(
            TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE,
            (qualification.checkpoint as TraversalCheckpointDocument.RetainedOutput).upstream,
        )
        assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= maximumBytes.value)
    }

    @Test
    fun `unencodable envelope does not allocate a continuation`() = runTest {
        val original = HostedTraversalPagingFixture.create().outcome
        var retentionCalls = 0
        val response =
            encodeHostedTraversalResponse(
                original,
                ReadLimits.Default,
                ResultLimit.parse(1).proven(),
                ReturnedByteLimit.parse(1).proven(),
            ) {
                retentionCalls += 1
                HostedOutputRetention.EncodingRejected
            }
        assertEquals(0, retentionCalls)
        assertEquals(HostedEndpointFailure.RESULT_TOO_LARGE, (response as HostedResponse.Rejected).failure)
    }

    @Test
    fun `retention failure never publishes a fitted page`() = runTest {
        val original = HostedTraversalPagingFixture.create().outcome
        val cases =
            listOf(
                HostedOutputRetention.CapacityExceeded to HostedEndpointFailure.RESULT_TOO_LARGE,
                HostedOutputRetention.EncodingRejected to HostedEndpointFailure.RESPONSE_REJECTED,
            )
        for ((retention, expected) in cases) {
            var retentionCalls = 0
            val response =
                encodeHostedTraversalResponse(
                    original,
                    ReadLimits.Default,
                    ResultLimit.parse(1).proven(),
                    defaultByteLimit(),
                ) {
                    retentionCalls += 1
                    retention
                }
            assertEquals(1, retentionCalls)
            assertEquals(expected, (response as HostedResponse.Rejected).failure)
        }
    }

    private fun defaultByteLimit(): ReturnedByteLimit =
        ReturnedByteLimit.parse(ReadLimits.Default[ReadLimitParameter.HOST_RESPONSE_BYTES].value.toLong()).proven()

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}

private const val OUTPUT_TOKEN = "traversal-output:v1:00000000-0000-0000-0000-000000000001"
