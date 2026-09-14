package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRelationReplayTest {
    @Test
    fun `lost response replays and larger page preserves every occurrence and provider continuation`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request = fixture.request(RelationReadPositionDocument.Start)
        val pages = store()
        val semantic = fixture.page()
        val first =
            encodeHostedRelationResponse(semantic, ReadLimits.Default, ResultLimit.parse(1).proven()) {
                    pages.issue(request, fixture.authority, it)
                }
                .qualified()
        val cursor = (first.qualification as RelationReadQualification.Resumable).continuation
        val resume =
            request.copy(
                position = RelationReadPositionDocument.Resume(cursor),
                limit = ProtocolCount.parse(4).proven(),
            )
        val token = ProtocolText.parse(cursor.value).proven()
        val restored = pages.restore(token, resume, fixture.authority)
        assertEquals(restored, pages.restore(token, resume, fixture.authority))
        val second =
            encodeHostedRelationResponse(restored, ReadLimits.Default, ResultLimit.parse(4).proven()) {
                    pages.issue(resume, fixture.authority, it)
                }
                .qualified()
        val next = (second.qualification as RelationReadQualification.Resumable).continuation
        val last = fixture.page(RelationReadPositionDocument.Resume(next)) as OperationOutcome.Complete
        val reference =
            fixture.protocol.execute(
                request.copy(limit = ProtocolCount.parse(4).proven()),
                fixture.authority,
                fixture.budget.copy(
                    resources = fixture.budget.resources.copy(resultLimit = ResultLimit.parse(4).proven())
                ),
            ) as OperationOutcome.Complete
        assertEquals(
            reference.evidence.payload.relations.values,
            first.evidence.payload.relations.values +
                second.evidence.payload.relations.values +
                last.evidence.payload.relations.values,
        )
        assertEquals(first.evidence.payload.omissions, second.evidence.payload.omissions)
    }

    @Test
    fun `replaying smaller page returns identical child token and changing relationship fails closed`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request = fixture.request(RelationReadPositionDocument.Start)
        val pages = store()
        val semantic = fixture.page()
        fun encode() =
            encodeHostedRelationResponse(semantic, ReadLimits.Default, ResultLimit.parse(1).proven()) {
                pages.issue(request, fixture.authority, it)
            }
        val first = encode()
        assertEquals(first.document, encode().document)
        val cursor = (first.qualified().qualification as RelationReadQualification.Resumable).continuation
        val changed = request.copy(relation = RelationKindDocument.CALLERS)
        assertEquals(
            OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_REQUEST_MISMATCH),
            pages.restore(ProtocolText.parse(cursor.value).proven(), changed, fixture.authority),
        )
    }

    @Test
    fun `expired and oversized retained suffixes return finite rejection`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request = fixture.request(RelationReadPositionDocument.Start)
        val outcome = fixture.page()
        var now = 0L
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_TTL_MILLIS" to "1")).proven()
        val pages = store(limits) { now }
        val issued = pages.issue(request, fixture.authority, outcome) as HostedOutputRetention.Retained
        now = 2_000_000L
        assertEquals(
            OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_UNAVAILABLE),
            pages.restore(issued.token, request, fixture.authority),
        )
        val tiny =
            store(
                ReadLimits.resolve(
                        environment =
                            mapOf(
                                "KAST_READ_QUERY_CONTINUATION_BYTES" to "1",
                                "KAST_READ_QUERY_CHECKPOINT_BYTES" to "1",
                            )
                    )
                    .proven()
            )
        assertEquals(HostedOutputRetention.CapacityExceeded, tiny.issue(request, fixture.authority, outcome))
    }

    @Test
    fun `exact expiry boundary is retained and replay never renews creation time`() = runTest {
        val fixture = RelationPagingFixture.live()
        val request = fixture.request(RelationReadPositionDocument.Start)
        val outcome = fixture.page()
        var now = 0L
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_TTL_MILLIS" to "1")).proven()
        val pages = store(limits) { now }
        val retained = pages.issue(request, fixture.authority, outcome) as HostedOutputRetention.Retained
        for (age in listOf(999_999L, 1_000_000L)) {
            now = age
            assertEquals(outcome, pages.restore(retained.token, request, fixture.authority))
            assertEquals(retained, pages.issue(request, fixture.authority, outcome))
        }
        now = 1_000_001L
        assertEquals(
            OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_UNAVAILABLE),
            pages.restore(retained.token, request, fixture.authority),
        )
    }

    @Test
    fun `entry eviction uses creation order despite replay and clear retires retained output`() = runTest {
        val authority = RelationPagingFixture.live().authority
        val fixtures = listOf("first", "second", "third").map { RelationPagingFixture(authority, it) }
        val requests = fixtures.map { it.request(RelationReadPositionDocument.Start) }
        val outcomes = fixtures.map { it.page() }
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CONTINUATION_ENTRIES" to "2")).proven()
        val pages = store(limits)
        val first = pages.issue(requests[0], authority, outcomes[0]) as HostedOutputRetention.Retained
        val second = pages.issue(requests[1], authority, outcomes[1]) as HostedOutputRetention.Retained
        assertEquals(outcomes[0], pages.restore(first.token, requests[0], authority))
        assertEquals(first, pages.issue(requests[0], authority, outcomes[0]))
        val third = pages.issue(requests[2], authority, outcomes[2]) as HostedOutputRetention.Retained
        val unavailable = OperationOutcome.Rejected(RelationReadRejection.CONTINUATION_UNAVAILABLE)
        assertEquals(unavailable, pages.restore(first.token, requests[0], authority))
        assertEquals(outcomes[1], pages.restore(second.token, requests[1], authority))
        assertEquals(outcomes[2], pages.restore(third.token, requests[2], authority))
        pages.clear()
        assertEquals(unavailable, pages.restore(second.token, requests[1], authority))
        assertEquals(unavailable, pages.restore(third.token, requests[2], authority))
    }

    @Test
    fun `envelope below minimum capacity never retains or publishes an empty cursor page`() = runTest {
        val fixture = RelationPagingFixture.live()
        val limits =
            ReadLimits.resolve(
                    environment =
                        mapOf(
                            "KAST_READ_HOST_RESPONSE_BYTES" to "256",
                            "KAST_READ_SEMANTIC_RETURNED_BYTES" to "1",
                            "KAST_READ_SOURCE_RETURNED_BYTES" to "1",
                        )
                )
                .proven()
        val response =
            encodeHostedRelationResponse(fixture.page(), limits) { error("An empty prefix must not retain a cursor") }
        assertTrue(response is HostedResponse.Oversized)
    }

    private fun store(limits: ReadLimits = ReadLimits.Default, clock: () -> Long = System::nanoTime) =
        HostedOutputPages(
            CanonicalOperationWireBindings.relationRead,
            RelationContinuationDocument.OUTPUT_PREFIX,
            limits,
            normalize = { request: RelationReadRequest ->
                request.copy(position = RelationReadPositionDocument.Start, limit = ProtocolCount.parse(1).proven())
            },
            unavailable = RelationReadRejection.CONTINUATION_UNAVAILABLE,
            mismatch = RelationReadRejection.CONTINUATION_REQUEST_MISMATCH,
            clock = clock,
        )

    @Suppress("UNCHECKED_CAST")
    private fun HostedResponse.qualified() =
        (this as HostedResponse.Canonical<*, *, *>).semantic
            as OperationOutcome.Qualified<RelationReadResult, RelationReadQualification>

    private fun <T> Refinement<T, *>.proven(): T = (this as Refinement.Refined).value
}
