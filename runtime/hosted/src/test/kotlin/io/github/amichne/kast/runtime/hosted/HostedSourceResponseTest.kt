package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedSourceResponseTest {
    @Test
    fun `oversized source page publishes a nonempty fitting entity prefix`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val original = fixture.outcome
        val outputs = hostedSourceOutputPages(ReadLimits.Default)
        val encoded = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, original)
        assertTrue(encoded is HostedResponse.Canonical<*, *, *>)
        val maximum = ReturnedByteLimit.parse(encoded.document.toByteArray().size.toLong() - 1).sourceFixtureValue()
        val response =
            encodeHostedSourceResponse(
                original,
                ReadLimits.Default,
                ResultLimit.parse(6).sourceFixtureValue(),
                maximum,
            ) { remaining ->
                outputs.issue(fixture.request, fixture.owner.authority, remaining)
            }
        assertTrue(response is HostedResponse.Canonical<*, *, *>, "Expected fitting source prefix, got $response")
        assertTrue(response.document.toByteArray().size <= maximum.value)
        val prefix = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val qualification = prefix.qualification as SourceReadQualification
        assertTerminalCoverage(qualification)
        val result = prefix.evidence.payload as SourceReadResult
        assertTrue(result.entities.values.isNotEmpty())
        assertTrue(SourceReadLimitationDocument.RETURNED_BYTE_LIMIT_REACHED in qualification.limitations)
        assertTrue(qualification.limitations.containsAll(original.qualification.limitations))
        val token = (qualification.continuation as SourceReadContinuationStateDocument.Available).continuation
        val request = fixture.request.copy(page = SourceReadPageDocument.Continue(token))
        val suffix = outputs.restore(token, request, fixture.owner.authority) as OperationOutcome.Qualified
        assertEquals(
            original.evidence.payload.entities.values,
            result.entities.values + suffix.evidence.payload.entities.values,
        )
        assertEquals(original.qualification, suffix.qualification)
        assertEquals(original.evidence.payload.text, result.text)
        assertEquals(suffix, outputs.restore(token, request, fixture.owner.authority))
    }

    private fun assertTerminalCoverage(qualification: SourceReadQualification) {
        val progress = qualification.progress as SourceQualifiedProgressDocument.Resumable
        assertEquals(ReadResumeActionDocument.RESUME, progress.nextAction)
        val checkpoint = progress.checkpoint as SourceCheckpointDocument.RetainedOutput
        assertEquals(
            SourcePreparedCoverageDocument.TerminalIncomplete(SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE),
            checkpoint.upstream,
        )
    }
}
