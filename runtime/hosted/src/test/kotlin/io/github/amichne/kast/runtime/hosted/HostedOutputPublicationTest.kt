package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadContinuationStateDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedOutputPublicationTest {
    @Test
    fun `suffix remains hidden through fitting and discard releases only its attempt`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(limits(entries = 2))
        val attempt = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val response =
            encodeHostedSourceResponse(fixture.outcome, ReadLimits.Default, results(1), bytes()) { attempt.issue(it) }
        val child = response.token()
        attempt.finish(response).sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, child, 65_536),
        )
        attempt.discard()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(fixture.request, fixture.owner.authority, child, 65_536),
        )
        store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue().discard()
    }

    @Test
    fun `one parent publishes one successor and replay retains the fitted page`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(limits(entries = 3))
        val parent = firstPage(store, fixture).token()
        val request = fixture.request.copy(page = SourceReadPageDocument.Continue(parent))
        val attempt = store.acquire(request, fixture.owner.authority, parent, 65_536).sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.IN_USE),
            store.acquire(request, fixture.owner.authority, parent, 65_536),
        )
        val retained = (attempt.input as HostedSourceInput.Retained).outcome
        val page = encodeHostedSourceResponse(retained, ReadLimits.Default, results(1), bytes()) { attempt.issue(it) }
        val successor = page.token()
        attempt.finish(page).sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(request, fixture.owner.authority, successor, 65_536),
        )
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.CAPACITY_EXCEEDED),
            store.acquire(fixture.request, fixture.owner.authority, null, 65_536),
        )
        attempt.commit().sourceFixtureValue()
        val replay = store.acquire(request, fixture.owner.authority, parent, 65_536).sourceFixtureValue()
        val published = (replay.input as HostedSourceInput.Published).outcome
        val replayed = HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, published)
        assertEquals(page.document, replayed.document)
        assertEquals(successor, replayed.token())
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.CAPACITY_EXCEEDED),
            store.acquire(fixture.request, fixture.owner.authority, null, 65_536),
        )
        replay.finish(replayed).sourceFixtureValue()
        replay.commit().sourceFixtureValue()
        store.acquire(request, fixture.owner.authority, successor, 65_536).sourceFixtureValue().discard()
    }

    @Test
    fun `expiry between fitting and acceptance cannot publish an expired successor`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        var now = 0L
        val store = HostedSourceStateStore(limits(entries = 3, ttl = 1), clock = { now })
        val parent = firstPage(store, fixture).token()
        val request = fixture.request.copy(page = SourceReadPageDocument.Continue(parent))
        val attempt = store.acquire(request, fixture.owner.authority, parent, 65_536).sourceFixtureValue()
        val page =
            encodeHostedSourceResponse(
                (attempt.input as HostedSourceInput.Retained).outcome,
                ReadLimits.Default,
                results(1),
                bytes(),
            ) {
                attempt.issue(it)
            }
        val successor = page.token()
        attempt.finish(page).sourceFixtureValue()
        now = 1_000_000L
        assertEquals(
            Refinement.Rejected(
                HostedQueryFailure.Publication(
                    io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause.EXPIRED
                )
            ),
            attempt.commit(),
        )
        attempt.discard()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(request, fixture.owner.authority, successor, 65_536),
        )
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(request, fixture.owner.authority, parent, 65_536),
        )
    }

    @Test
    fun `discarded resume preserves its parent facts and permits a fresh advancing attempt`() = runTest {
        val fixture = HostedSourcePagingFixture.create()
        val store = HostedSourceStateStore(limits(entries = 3))
        val parent = firstPage(store, fixture).token()
        val request = fixture.request.copy(page = SourceReadPageDocument.Continue(parent))
        val first = store.acquire(request, fixture.owner.authority, parent, 65_536).sourceFixtureValue()
        val facts = (first.input as HostedSourceInput.Retained).outcome
        val rejectedPage =
            encodeHostedSourceResponse(facts, ReadLimits.Default, results(1), bytes()) { first.issue(it) }
        first.finish(rejectedPage).sourceFixtureValue()
        first.discard()
        val next = store.acquire(request, fixture.owner.authority, parent, 65_536).sourceFixtureValue()
        assertEquals(facts, (next.input as HostedSourceInput.Retained).outcome)
        val acceptedPage = encodeHostedSourceResponse(facts, ReadLimits.Default, results(1), bytes()) { next.issue(it) }
        next.finish(acceptedPage).sourceFixtureValue()
        next.commit().sourceFixtureValue()
        assertEquals(
            Refinement.Rejected(HostedOutputAcquisitionFailure.UNAVAILABLE),
            store.acquire(request, fixture.owner.authority, rejectedPage.token(), 65_536),
        )
        store.acquire(request, fixture.owner.authority, acceptedPage.token(), 65_536).sourceFixtureValue().discard()
    }

    private fun firstPage(store: SourceOutputFixtureStore, fixture: HostedSourcePagingFixture): HostedResponse {
        val initial = store.acquire(fixture.request, fixture.owner.authority, null, 65_536).sourceFixtureValue()
        val page =
            encodeHostedSourceResponse(fixture.outcome, ReadLimits.Default, results(1), bytes()) { initial.issue(it) }
        initial.finish(page).sourceFixtureValue()
        initial.commit().sourceFixtureValue()
        return page
    }

    private fun HostedResponse.token(): ProtocolText {
        val page = assertInstanceOf(HostedResponse.Canonical::class.java, this)
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, page.semantic)
        val coverage = assertInstanceOf(SourceReadQualification::class.java, qualified.qualification)
        return assertInstanceOf(SourceReadContinuationStateDocument.Available::class.java, coverage.continuation)
            .continuation
    }

    private fun results(count: Int) = ResultLimit.parse(count).sourceFixtureValue()

    private fun bytes() = ReturnedByteLimit.parse(65_536).sourceFixtureValue()

    private fun limits(entries: Int, ttl: Int = 600_000) =
        ReadLimits.resolve(
                environment =
                    mapOf(
                        ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.environmentKey to entries.toString(),
                        ReadLimitParameter.QUERY_CONTINUATION_TTL_MILLIS.environmentKey to ttl.toString(),
                    )
            )
            .sourceFixtureValue()
}
