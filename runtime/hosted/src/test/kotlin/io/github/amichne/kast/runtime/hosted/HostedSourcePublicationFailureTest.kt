package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedSourcePublicationFailureTest {
    @Test
    fun `expired cursor fitting cannot publish and discarded attempts release shared quota`() = runTest {
        var now = 0L
        val fixture = HostedSourcePagingFixture.create()
        val limits =
            sourceCursorLimits(
                ReadLimitParameter.QUERY_CONTINUATION_ENTRIES to "2",
                ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS to "1",
            )
        val store = HostedSourceStateStore(limits, clock = { now })
        val session = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        val token = ProtocolText.parse(session.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        session.finish(sourceCursorFitted(fixture, token)).sourceFixtureValue()
        now = 1_000_000L
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.EXPIRED)),
            session.commit(),
        )
        session.discard()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, token, 65_536),
        )
        store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue().discard()
    }

    @Test
    fun `cursor capacity rejection is distinct from retirement and nonadvancement`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES to "1"))
        val session = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        assertEquals(Refinement.Rejected(SourceReadCursorRetentionFailure.CAPACITY_EXCEEDED), session.issue(proof))
        store.retire()
        assertEquals(Refinement.Rejected(SourceReadCursorRetentionFailure.OWNER_UNAVAILABLE), session.issue(proof))
        session.discard()
    }

    @Test
    fun `publication preserves missing dependency retirement and immutable replay failures`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(ReadLimits.Default)
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val proof = sourceCursorProof(fixture)
        val native = ProtocolText.parse(first.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        first.finish(sourceCursorFitted(fixture, native)).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        val resumed = store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue()
        val missing = ProtocolText.parse("source-read-continuation-v1|" + "0".repeat(64)).sourceFixtureValue()
        resumed.finish(sourceCursorFitted(fixture, missing)).sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE)),
            resumed.commit(),
        )
        resumed.discard()
        val accepted = store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue()
        val final = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, fixture.outcome)
        accepted.finish(final).sourceFixtureValue()
        accepted.commit().sourceFixtureValue()
        val replay = store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue()
        replay.finish(sourceCursorFitted(fixture, native)).sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH)),
            replay.commit(),
        )
        replay.discard()
        val retired = store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue()
        retired.finish(final).sourceFixtureValue()
        store.retire()
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.OWNER_RETIRED)),
            retired.commit(),
        )
        retired.discard()
    }
}
