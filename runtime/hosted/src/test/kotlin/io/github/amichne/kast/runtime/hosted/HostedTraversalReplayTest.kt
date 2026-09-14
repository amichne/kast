package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedTraversalReplayTest {
    @Test
    fun `byte only fitting drains stable replayable suffixes within the envelope allowance`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        val pages = hostedTraversalOutputPages(ReadLimits.Default)
        val maximum = byteLimit(encoded(bytePrefix(fixture)).document)
        val combined = mutableListOf<io.github.amichne.kast.protocol.contract.TraversalRecordDocument>()
        val tokens = mutableSetOf<ProtocolText>()
        var remaining: HostedTraversalOutcome = fixture.outcome
        val request = fixture.request.copy(maximumResults = ProtocolCount.parse(9).proven())
        repeat(3) { index ->
            fun encode() =
                encodeHostedTraversalResponse(
                    semantic = remaining,
                    limits = ReadLimits.Default,
                    maximumResults = ResultLimit.parse(9).proven(),
                    maximumBytes = maximum,
                ) {
                    pages.issue(request, fixture.owner.authority, it)
                }
            val response = encode()
            assertEquals(response.document, encode().document)
            assertTrue(response.document.toByteArray(Charsets.UTF_8).size <= maximum.value)
            val semantic = response.qualified()
            val result = semantic.evidence.payload
            assertEquals(1, result.records.values.size)
            combined += result.records.values
            assertEquals(fixture.outcome.evidence.payload.partialExpansions, result.partialExpansions)
            assertEquals(fixture.outcome.evidence.payload.progress, result.progress)
            if (index < 2) {
                val token = assertRetainedBytePage(semantic.qualification)
                assertTrue(tokens.add(token), "A retained page must advance to a distinct suffix")
                val cursor = TraversalContinuationDocument.parse(token.value).proven()
                val resume = request.copy(position = TraversalRunPositionDocument.Resume(cursor))
                val restored = pages.restore(token, resume, fixture.owner.authority)
                assertEquals(restored, pages.restore(token, resume, fixture.owner.authority))
                assertNotEquals(remaining, restored)
                remaining = restored
            } else assertEquals(fixture.outcome.qualification, semantic.qualification)
        }
        assertEquals(fixture.outcome.evidence.payload.records.values, combined)
        assertEquals(3, combined.map { it.relation.occurrence }.distinct().size)
        assertEquals(2, tokens.size)
        assertEquals(listOf(0L, 1L, 2L), fixture.owner.consumed)
    }

    @Test
    fun `one indivisible record and mandatory metadata reject without retaining an empty page`() = runTest {
        val fixture = HostedTraversalPagingFixture.create()
        for (recordCount in listOf(0, 1)) {
            val outcome =
                fixture.outcome.copy(
                    evidence =
                        fixture.outcome.evidence.copy(
                            payload =
                                fixture.outcome.evidence.payload.copy(
                                    records =
                                        BoundedProtocolList.create(
                                                fixture.outcome.evidence.payload.records.values.take(recordCount)
                                            )
                                            .proven()
                                )
                        )
                )
            val maximum =
                ReturnedByteLimit.parse(encoded(outcome).document.toByteArray(Charsets.UTF_8).size - 1L).proven()
            var retentionCalls = 0
            val response =
                encodeHostedTraversalResponse(
                    semantic = outcome,
                    limits = ReadLimits.Default,
                    maximumResults = ResultLimit.parse(9).proven(),
                    maximumBytes = maximum,
                ) {
                    retentionCalls += 1
                    HostedOutputRetention.CapacityExceeded
                }
            assertEquals(HostedEndpointFailure.RESULT_TOO_LARGE, (response as HostedResponse.Rejected).failure)
            assertEquals(0, retentionCalls)
        }
    }

    private fun assertRetainedBytePage(qualification: TraversalRunQualification): ProtocolText {
        val resumable = qualification as TraversalRunQualification.Resumable
        assertTrue(TraversalLimitationDocument.BYTE_LIMIT_REACHED in resumable.limitations)
        assertFalse(TraversalLimitationDocument.RECORD_LIMIT_REACHED in resumable.limitations)
        assertEquals(ReadResumeActionDocument.RESUME, resumable.nextAction)
        val checkpoint = resumable.checkpoint as TraversalCheckpointDocument.RetainedOutput
        assertEquals(TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE, checkpoint.upstream)
        return ProtocolText.parse(checkpoint.token.value).proven()
    }

    private fun bytePrefix(fixture: HostedTraversalPagingFixture): HostedTraversalOutcome =
        fixture.outcome.copy(
            evidence =
                fixture.outcome.evidence.copy(
                    payload =
                        fixture.outcome.evidence.payload.copy(
                            records =
                                BoundedProtocolList.create(fixture.outcome.evidence.payload.records.values.take(1))
                                    .proven()
                        )
                ),
            qualification =
                TraversalRunQualification.admitResumable(
                        limitations =
                            (fixture.outcome.qualification.limitations + TraversalLimitationDocument.BYTE_LIMIT_REACHED)
                                .sortedBy { it.ordinal },
                        relationLimitations = fixture.outcome.qualification.relationLimitations,
                        checkpoint =
                            TraversalCheckpointDocument.RetainedOutput(
                                TraversalContinuationDocument.parse(
                                        "traversal-output:v1:00000000-0000-0000-0000-000000000001"
                                    )
                                    .proven(),
                                TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE,
                            ),
                        nextAction = ReadResumeActionDocument.RESUME,
                    )
                    .proven(),
        )

    private fun encoded(outcome: HostedTraversalOutcome) =
        HostedResponse.Canonical.encode(CanonicalOperationWireBindings.traversalRun, outcome)
            as HostedResponse.Canonical<*, *, *>

    private fun byteLimit(document: String) =
        ReturnedByteLimit.parse(document.toByteArray(Charsets.UTF_8).size.toLong()).proven()

    @Suppress("UNCHECKED_CAST")
    private fun HostedResponse.qualified() =
        (this as HostedResponse.Canonical<*, *, *>).semantic
            as OperationOutcome.Qualified<TraversalRunResult, TraversalRunQualification>

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
