package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourceOutputOwnershipTest {
    @Test
    fun `native and fitted output successors publish atomically and pin their shared dependency`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES to "3"))
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        val native = ProtocolText.parse(first.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        val semantic =
            (sourceCursorFitted(fixture, native) as HostedResponse.Canonical<*, *, *>).semantic as HostedSourceOutcome
        val response =
            encodeHostedSourceResponse(
                semantic,
                ReadLimits.Default,
                ResultLimit.parse(1).sourceFixtureValue(),
                ReturnedByteLimit.parse(65_536).sourceFixtureValue(),
                first::issue,
            )
        val prefix = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val output =
            (prefix.qualification as SourceReadQualification).continuation
                as io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument.Available
        for (token in listOf(native, output.continuation)) assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, token, 65_536),
        )
        first.finish(response).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        val resumed =
            store.acquire(fixture.request, fixture.owner.authority, output.continuation, 65_536).sourceFixtureValue()
        val suffix = (resumed.input as HostedSourceInput.Retained).outcome
        assertEquals(native, suffix.sourceContinuation())
        // The cursor, retained output and its active claim fill the one three-entry quota. The
        // native cursor is an output dependency, so pressure must not evict it behind this suffix.
        val narrowed = suffix as OperationOutcome.Qualified
        val remaining =
            OperationOutcome.Qualified(
                narrowed.evidence.copy(
                    payload =
                        narrowed.evidence.payload.copy(
                            entities =
                                BoundedProtocolList.create(narrowed.evidence.payload.entities.values.drop(1))
                                    .sourceFixtureValue()
                        )
                ),
                narrowed.qualification,
            )
        assertEquals(HostedOutputRetention.CapacityExceeded, resumed.issue(remaining))
        resumed.discard()
        store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue().discard()
        store.checkSourceCursorAvailable(fixture, output.continuation)
    }

    @Test
    fun `terminal fitting retains proven entities and prunes a no longer advertised native child`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES to "2"))
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        val native = ProtocolText.parse(first.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        val before = store.retentionMeasurements()
        assertEquals(2, before.retainedEntries.value)
        val original =
            (sourceCursorFitted(fixture, native) as HostedResponse.Canonical<*, *, *>).semantic as HostedSourceOutcome
        val response =
            encodeHostedSourceResponse(
                original,
                ReadLimits.Default,
                ResultLimit.parse(1).sourceFixtureValue(),
                ReturnedByteLimit.parse(65_536).sourceFixtureValue(),
                first::issue,
            )
        val qualified = (response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified
        val qualification = qualified.qualification as SourceReadQualification
        assertEquals(
            fixture.outcome.evidence.payload.entities.values.take(1),
            (qualified.evidence.payload as io.github.amichne.kast.protocol.contract.SourceReadResult).entities.values,
        )
        assertEquals(
            SourceQualifiedProgressDocument.RetentionUnavailable(
                io.github.amichne.kast.protocol.contract.SourcePreparedCoverageDocument.Resumable
            ),
            qualification.progress,
        )
        first.finish(response).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        assertEquals(0, store.entries.size)
        val after = store.retentionMeasurements()
        assertEquals(0L, after.retainedBytes.value)
        assertEquals(before.highWaterBytes, after.highWaterBytes)
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, native, 65_536),
        )
        store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue().discard()
    }
}
