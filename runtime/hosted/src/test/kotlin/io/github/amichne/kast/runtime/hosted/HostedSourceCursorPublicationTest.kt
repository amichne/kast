package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadPage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourceCursorPublicationTest {
    @Test
    fun `native cursor is hidden until the same fitted source page commits and replay is immutable`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(ReadLimits.Default)
        val session = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val request = sourceCursorRequest(fixture)
        val first = session.admit(fixture.selected.snapshot.context, request).sourceFixtureValue()
        val proof =
            SourceReadCursorProof.create(request, fixture.selected, first, 1, sourceCursorTraversal(fixture, 1))
                .sourceFixtureValue()
        val native = session.issue(proof).sourceFixtureValue()
        val token = ProtocolText.parse(native.value).sourceFixtureValue()
        val resumedWire = fixture.request.copy(page = SourceReadPageDocument.Continue(token))
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(resumedWire, fixture.owner.authority, token, 65_536),
        )
        val response = sourceCursorFitted(fixture, token)
        session.finish(response).sourceFixtureValue()
        session.commit().sourceFixtureValue()
        val resumed = store.acquire(resumedWire, fixture.owner.authority, token, 65_536).sourceFixtureValue()
        val admitted =
            resumed
                .admit(fixture.selected.snapshot.context, request.copy(page = SourceReadPage.Continue(native)))
                .sourceFixtureValue()
        assertEquals(1, admitted.startOrdinal)
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.IN_USE),
            store.acquire(resumedWire, fixture.owner.authority, token, 65_536),
        )
        assertEquals(Refinement.Rejected(SourceReadCursorRetentionFailure.INVALID_STATE), resumed.issue(proof))
        val next =
            SourceReadCursorProof.create(request, fixture.selected, admitted, 2, sourceCursorTraversal(fixture, 2))
                .sourceFixtureValue()
        val successor = ProtocolText.parse(resumed.issue(next).sourceFixtureValue().value).sourceFixtureValue()
        val published = sourceCursorFitted(fixture, successor)
        resumed.finish(published).sourceFixtureValue()
        resumed.commit().sourceFixtureValue()
        val replay = store.acquire(resumedWire, fixture.owner.authority, token, 65_536).sourceFixtureValue()
        assertEquals(
            (published as HostedResponse.Canonical<*, *, *>).semantic,
            (replay.input as HostedSourceInput.Published).outcome,
        )
        replay.finish(published).sourceFixtureValue()
        replay.commit().sourceFixtureValue()
        store.checkSourceCursorAvailable(fixture, successor)
    }

    @Test
    fun `structural progress advances a source cursor before another eligible entity is found`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(ReadLimits.Default)
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val request = sourceCursorRequest(fixture)
        val proof = structuralCursor(fixture, request, SourceReadEntityCursor.First, 1)
        val cursor = first.issue(proof).sourceFixtureValue()
        val token = ProtocolText.parse(cursor.value).sourceFixtureValue()
        first.finish(sourceCursorFitted(fixture, token)).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        val resumed = store.acquire(fixture.request, fixture.owner.authority, token, 65_536).sourceFixtureValue()
        val admitted =
            resumed
                .admit(
                    fixture.selected.snapshot.context,
                    request.copy(page = SourceReadPage.Continue(cursor)),
                )
                .sourceFixtureValue()
        val next = structuralCursor(fixture, request, admitted, 2)
        val successor = ProtocolText.parse(resumed.issue(next).sourceFixtureValue().value).sourceFixtureValue()
        resumed.finish(sourceCursorFitted(fixture, successor)).sourceFixtureValue()
        resumed.commit().sourceFixtureValue()
        val final = store.acquire(fixture.request, fixture.owner.authority, successor, 65_536).sourceFixtureValue()
        val nextCursor =
            io.github.amichne.kast.source.contract.SourceReadContinuation.parse(successor.value).sourceFixtureValue()
        val restored =
            final
                .admit(
                    fixture.selected.snapshot.context,
                    request.copy(page = SourceReadPage.Continue(nextCursor)),
                )
                .sourceFixtureValue()
        assertEquals(0, restored.startOrdinal)
        assertEquals(2L, (restored as SourceReadEntityCursor.Continued).proof.traversal.revision)
        final.discard()
    }

    private fun structuralCursor(
        fixture: HostedSourcePagingFixture,
        request: io.github.amichne.kast.source.contract.SourceReadRequest,
        previous: SourceReadEntityCursor,
        revision: Long,
    ) =
        SourceReadCursorProof.create(request, fixture.selected, previous, 0, sourceCursorTraversal(fixture, revision))
            .sourceFixtureValue()
}
