package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** A replay pins detached storage until drainage; it never extends the original admission lifetime. */
class DiagnosticAdmissionExpiryTest {
    private val fixture = DiagnosticPublicationFixture()

    @Test
    fun `active replay cannot admit its expired output successor`() = runTest {
        with(fixture) {
            var now = 0L
            val store = DiagnosticCheckpointStore(ttlMillis = 1, clock = { now })
            val publication = DiagnosticPublicationFixture.Capture()
            var scans = 0
            val protocol =
                protocol(store, publication) {
                    scans++
                    completed()
                }
            val semantic = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            val suffix = semantic.dropFacts(1)
            val token = store.issueOutput(publication.claim, suffix, measure(suffix)).refined()
            val published = prefix(semantic, 1, token)
            store.commit(publication.claim, published).refined()
            assertEquals(published, protocol.execute(request, owner.authority, budget))
            val replayClaim = publication.claim

            now = 1_000_000L
            assertEquals(
                OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
                protocol.execute(request.copy(continuation = token), owner.authority, budget),
            )
            assertSame(replayClaim, publication.claim)
            assertEquals(1, scans)
            assertEquals(
                Refinement.Rejected(DiagnosticPublicationFailure.EXPIRED),
                store.commit(replayClaim, published),
            )
            store.discard(replayClaim)
            assertEquals(0L, store.retentionMeasurements().retainedBytes.value)
        }
    }

    @Test
    fun `active replay cannot deduplicate a fresh request against expired facts`() = runTest {
        with(fixture) {
            var now = 0L
            val store = DiagnosticCheckpointStore(ttlMillis = 1, clock = { now })
            val publication = DiagnosticPublicationFixture.Capture()
            var scans = 0
            val protocol =
                protocol(store, publication) {
                    scans++
                    completed()
                }
            val published = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            store.commit(publication.claim, published).refined()
            assertEquals(published, protocol.execute(request, owner.authority, budget))
            val replayClaim = publication.claim

            now = 1_000_000L
            assertEquals(
                OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
                protocol.execute(request, owner.authority, budget),
            )
            assertSame(replayClaim, publication.claim)
            assertEquals(1, scans)
            assertEquals(
                Refinement.Rejected(DiagnosticPublicationFailure.EXPIRED),
                store.commit(replayClaim, published),
            )
            store.discard(replayClaim)
            val fresh = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            assertEquals(2, scans)
            store.commit(publication.claim, fresh).refined()
        }
    }
}
