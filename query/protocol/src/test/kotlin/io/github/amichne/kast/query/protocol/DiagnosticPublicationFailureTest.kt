package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStage
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ownership tests over detached established facts; no native compiler claims. */
class DiagnosticPublicationFailureTest {
    private val fixture = DiagnosticPublicationFixture()

    @Test
    fun `scan retention refusal preserves established diagnostics and exact inventory without a successor`() = runTest {
        with(fixture) {
            val store = DiagnosticCheckpointStore(maximumCheckpointBytes = 1)
            val publication = DiagnosticPublicationFixture.Capture()
            val checkpoint =
                object : io.github.amichne.kast.diagnostic.contract.DiagnosticScanCheckpoint {
                    override val query = fixture.query
                    override val retainedBytes = 2L
                }
            val protocol =
                protocol(store, publication) {
                    DiagnosticScanResult.Advancing(
                        completed().page,
                        checkpoint,
                        io.github.amichne.kast.diagnostic.contract.DiagnosticScanStop.OutputPending,
                    )
                }
            val result = protocol.execute(request, owner.authority, budget) as OperationOutcome.Qualified
            assertEquals(facts.map { it.code.value }, result.evidence.payload.diagnostics.values.map { it.code.value })
            assertEquals(null, result.qualification.continuation)
            assertEquals(
                io.github.amichne.kast.protocol.contract.DiagnosticRetentionFailureDocument.CAPACITY_EXCEEDED,
                result.qualification.retentionFailure,
            )
            val progress = result.evidence.payload.progress!!
            assertEquals(
                io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument.Exhausted(
                    ProtocolCount.parse(1).refined()
                ),
                progress.inventory,
            )
            assertEquals(4, progress.knownDiagnosticCount.value)
            assertEquals(DiagnosticProgressStage.FINISHED, progress.stage)
            assertEquals(DiagnosticProgressStop.RETENTION_CAPACITY_EXCEEDED, progress.stop)
            store.commit(publication.claim, result).refined()
        }
    }

    @Test
    fun `expiry during detached fitting rejects publication and revokes hidden work`() = runTest {
        with(fixture) {
            var now = 0L
            val store = DiagnosticCheckpointStore(ttlMillis = 1, clock = { now })
            val publication = DiagnosticPublicationFixture.Capture()
            val protocol = protocol(store, publication) { completed() }
            val semantic = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            val suffix = semantic.dropFacts(1)
            val token = store.issueOutput(publication.claim, suffix, measure(suffix)).refined()
            now = 1_000_000L
            assertEquals(
                Refinement.Rejected(DiagnosticPublicationFailure.EXPIRED),
                store.commit(publication.claim, prefix(semantic, 1, token)),
            )
            store.discard(publication.claim)
            assertEquals(
                OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
                protocol.execute(request.copy(continuation = token), owner.authority, budget),
            )
        }
    }

    @Test
    fun `cancellation drains an acquired attempt without retaining first-request ownership`() = runTest {
        with(fixture) {
            val store = DiagnosticCheckpointStore(capacity = 2)
            var scans = 0
            val protocol =
                protocol(store, DiagnosticPublicationFixture.Capture()) {
                    scans++
                    if (scans == 1) throw CancellationException("case-owned cancellation")
                    completed()
                }
            try {
                protocol.execute(request, owner.authority, budget)
                org.junit.jupiter.api.Assertions.fail<Unit>("Cancellation must propagate after attempt cleanup")
            } catch (cancelled: CancellationException) {
                assertEquals("case-owned cancellation", cancelled.message)
            }
            assertEquals(
                completed().page.facts.size,
                (protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete)
                    .evidence
                    .payload
                    .diagnostics
                    .values
                    .size,
            )
            assertEquals(2, scans)
        }
    }

    @Test
    fun `final diagnostic publication distinguishes unavailable claim retired owner and cached page mismatch`() =
        runTest {
            with(fixture) {
                val store = DiagnosticCheckpointStore()
                val publication = DiagnosticPublicationFixture.Capture()
                val protocol = protocol(store, publication) { completed() }
                val semantic = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
                val firstClaim = publication.claim
                store.commit(firstClaim, semantic).refined()
                assertEquals(
                    Refinement.Rejected(DiagnosticPublicationFailure.CLAIM_UNAVAILABLE),
                    store.commit(firstClaim, semantic),
                )
                protocol.execute(request, owner.authority, budget)
                val changed = semantic.dropFacts(1)
                assertEquals(
                    Refinement.Rejected(DiagnosticPublicationFailure.PUBLISHED_PAGE_MISMATCH),
                    store.commit(publication.claim, changed),
                )
                store.discard(publication.claim)
                protocol.execute(request, owner.authority, budget)
                store.retire()
                assertEquals(
                    Refinement.Rejected(DiagnosticPublicationFailure.OWNER_RETIRED),
                    store.commit(publication.claim, semantic),
                )
                store.discard(publication.claim)
            }
        }
}
