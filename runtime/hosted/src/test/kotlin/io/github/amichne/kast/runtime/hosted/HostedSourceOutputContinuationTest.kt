package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedSourceOutputContinuationTest {
    @Test
    fun `higher allowances drain the retained source prefix without erasing terminal coverage`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val outputs = hostedSourceOutputPages(ReadLimits.Default)
        val small = fixture.request.copy(entityLimit = SourceEntityLimitDocument.parse(1).sourceFixtureValue())
        fun firstPage() =
            encodeHostedSourceResponse(fixture.outcome, ReadLimits.Default, results(1), bytes(65_536)) {
                outputs.issue(small, fixture.owner.authority, it)
            }
        val first = firstPage().qualified()
        val token = first.token()
        val higher =
            small.copy(
                entityLimit = SourceEntityLimitDocument.parse(100).sourceFixtureValue(),
                textByteLimit = SourceTextByteLimitDocument.parse(100_000).sourceFixtureValue(),
                executionBudget = ExecutionBudgetDocument(maxResults = results(100), maxReturnedBytes = bytes(65_536)),
                page = SourceReadPageDocument.Continue(token),
            )
        val restored = outputs.restore(token, higher, fixture.owner.authority)
        val final =
            encodeHostedSourceResponse(restored, ReadLimits.Default, results(100), bytes(65_536)) {
                    error("The restored suffix fits this larger grant")
                }
                .qualified()
        val firstEntities = (first.evidence.payload as SourceReadResult).entities.values
        val finalEntities = (final.evidence.payload as SourceReadResult).entities.values
        assertEquals(fixture.outcome.evidence.payload.entities.values, firstEntities + finalEntities)
        assertEquals(fixture.outcome.qualification, final.qualification)
        assertEquals(token, firstPage().qualified().token())
        assertEquals(restored, outputs.restore(token, higher, fixture.owner.authority))
    }

    @Test
    fun `source output continuation binds projection and scope and fails after retirement`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val outputs = hostedSourceOutputPages(ReadLimits.Default)
        val retained =
            outputs.issue(fixture.request, fixture.owner.authority, fixture.outcome) as HostedOutputRetention.Retained
        val changed =
            listOf(
                fixture.request.copy(region = SourceRegionSelectionDocument.Anchor),
                fixture.request.copy(entities = SourceEntitySelectionDocument.None),
                fixture.request.copy(text = SourceTextRequestDocument.Complete),
            )
        changed.forEach { request ->
            assertEquals(
                OperationOutcome.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH),
                outputs.restore(retained.token, request, fixture.owner.authority),
            )
        }
        assertEquals(
            OperationOutcome.Rejected(SourceReadRejection.CONTINUATION_REQUEST_MISMATCH),
            outputs.restore(retained.token, fixture.request, HostedSourcePagingFixture.create().owner.authority),
        )
        outputs.clear()
        assertEquals(
            OperationOutcome.Rejected(SourceReadRejection.CONTINUATION_UNAVAILABLE),
            outputs.restore(retained.token, fixture.request, fixture.owner.authority),
        )
    }

    @Test
    fun `unretainable suffix and indivisible response reject without empty cursor loops`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            ReadLimitParameter.QUERY_CONTINUATION_BYTES.environmentKey to "1",
                            ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "1",
                        )
                )
                .sourceFixtureValue()
        val outputs = hostedSourceOutputPages(limits)
        val capacity =
            encodeHostedSourceResponse(fixture.outcome, limits, results(1), bytes(65_536)) {
                outputs.issue(fixture.request, fixture.owner.authority, it)
            }
        assertEquals(HostedEndpointFailure.RESULT_TOO_LARGE, (capacity as HostedResponse.Rejected).failure)
        val indivisible =
            encodeHostedSourceResponse(fixture.outcome, ReadLimits.Default, results(1), bytes(1)) {
                error("No entity plus mandatory envelope fits; no cursor may be issued")
            }
        assertInstanceOf(HostedResponse.Oversized::class.java, indivisible)
    }

    private fun HostedResponse.qualified() =
        (this as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified

    private fun OperationOutcome.Qualified<*, *>.token(): ProtocolText =
        ((qualification as SourceReadQualification).continuation as SourceReadContinuationStateDocument.Available)
            .continuation

    private fun results(value: Int) = ResultLimit.parse(value).sourceFixtureValue()

    private fun bytes(value: Long) = ReturnedByteLimit.parse(value).sourceFixtureValue()
}
