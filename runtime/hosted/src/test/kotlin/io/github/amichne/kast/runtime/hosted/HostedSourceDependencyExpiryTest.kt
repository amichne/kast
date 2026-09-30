package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourceDependencyExpiryTest {
    @Test
    fun `younger retained output cannot extend a deduplicated native cursor age`() = runTest {
        var now = 0L
        val fixture = HostedSourcePagingFixture.create()
        val store =
            HostedSourceStateStore(
                sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS to "1"),
                clock = { now },
            )
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        val native = ProtocolText.parse(first.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        first.finish(sourceCursorFitted(fixture, native)).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        now = 500_000L
        val second = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        assertEquals(native.value, second.issue(proof).sourceFixtureValue().value)
        val semantic =
            (sourceCursorFitted(fixture, native) as HostedResponse.Canonical<*, *, *>).semantic as HostedSourceOutcome
        val response =
            encodeHostedSourceResponse(
                semantic,
                ReadLimits.Default,
                ResultLimit.parse(1).sourceFixtureValue(),
                ReturnedByteLimit.parse(65_536).sourceFixtureValue(),
                second::issue,
            )
        val prefix = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val output =
            (prefix.qualification as SourceReadQualification).continuation
                as SourceReadContinuationStateDocument.Available
        second.finish(response).sourceFixtureValue()
        second.commit().sourceFixtureValue()
        assertEquals(0L, store.entries.getValue(native).createdAt)
        assertEquals(500_000L, store.entries.getValue(output.continuation).createdAt)
        now = 1_100_000L
        for (token in listOf(native, output.continuation)) assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, token, 65_536),
        )
        assertEquals(0, store.entries.size)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }
}
