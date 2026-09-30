package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedSourceClaimExpiryTest {
    @Test
    fun `a younger active claim preserves storage but cannot publish an expired dependency`() = runTest {
        var now = 0L
        val fixture = HostedSourcePagingFixture.create()
        val store =
            HostedSourceStateStore(
                sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS to "1"),
                clock = { now },
            )
        val proof = sourceCursorProof(fixture)
        val native = publishNativeCursor(store, fixture, proof)
        now = 500_000L
        val younger = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        assertEquals(native.value, younger.issue(proof).sourceFixtureValue().value)
        younger.finish(sourceCursorFitted(fixture, native)).sourceFixtureValue()
        now = 1_100_000L
        store.expire()
        assertTrue(native in store.entries)
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, native, 65_536),
        )
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.EXPIRED)),
            younger.commit(),
        )
        younger.discard()
        assertFalse(native in store.entries)
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    @Test
    fun `a fresh initial read cannot deduplicate into another running native cursor`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(ReadLimits.Default)
        val proof = sourceCursorProof(fixture)
        val native = publishNativeCursor(store, fixture, proof)
        val running = store.acquire(fixture.request, fixture.owner.authority, native, 65_536).sourceFixtureValue()
        val fresh = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val independent = ProtocolText.parse(fresh.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        assertNotEquals(native, independent)
        assertTrue(store.entries.getValue(native).execution is HostedSourceStateStore.Execution.Running)
        fresh.discard()
        running.discard()
        store.checkSourceCursorAvailable(fixture, native)
    }

    @Test
    fun `a fresh initial read cannot deduplicate into an expired dependency pinned by a younger claim`() = runTest {
        var now = 0L
        val fixture = HostedSourcePagingFixture.create()
        val store =
            HostedSourceStateStore(
                sourceCursorLimits(ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS to "1"),
                clock = { now },
            )
        val proof = sourceCursorProof(fixture)
        val native = publishNativeCursor(store, fixture, proof)
        now = 500_000L
        val holder = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        assertEquals(native.value, holder.issue(proof).sourceFixtureValue().value)
        now = 1_100_000L
        val fresh = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        assertTrue(native in store.entries)
        val independent = ProtocolText.parse(fresh.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        assertNotEquals(native, independent)
        assertEquals(0L, store.entries.getValue(native).createdAt)
        assertEquals(now, store.entries.getValue(independent).createdAt)
        holder.discard()
        fresh.discard()
        assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
    }

    private fun publishNativeCursor(
        store: HostedSourceStateStore,
        fixture: HostedSourcePagingFixture,
        proof: SourceReadCursorProof,
    ): ProtocolText {
        val first = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val native = ProtocolText.parse(first.issue(proof).sourceFixtureValue().value).sourceFixtureValue()
        first.finish(sourceCursorFitted(fixture, native)).sourceFixtureValue()
        first.commit().sourceFixtureValue()
        return native
    }
}
