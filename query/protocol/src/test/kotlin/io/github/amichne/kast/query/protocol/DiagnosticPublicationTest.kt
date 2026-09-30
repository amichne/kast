package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Ownership tests over detached established facts; no native compiler claims. */
class DiagnosticPublicationTest {
    private val fixture = DiagnosticPublicationFixture()

    @Test
    fun `fitted prefix and suffix commit together and replay preserves exact facts and successor`() = runTest {
        with(fixture) {
            val store = DiagnosticCheckpointStore(capacity = 5)
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
            assertEquals(
                OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
                protocol.execute(request.copy(continuation = token), owner.authority, budget),
            )
            assertEquals(
                OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_IN_USE),
                protocol.execute(request, owner.authority, budget),
            )
            val prefix = prefix(semantic, 1, token)
            store.commit(publication.claim, prefix).refined()
            assertEquals(prefix, protocol.execute(request, owner.authority, budget))
            store.commit(publication.claim, prefix).refined()
            val restored =
                protocol.execute(request.copy(continuation = token), owner.authority, budget)
                    as OperationOutcome.Complete
            assertEquals(suffix, restored)
            val last = restored.dropFacts(2)
            val successor = store.issueOutput(publication.claim, last, measure(last)).refined()
            val resumedPrefix = prefix(restored, 2, successor)
            store.commit(publication.claim, resumedPrefix).refined()
            assertEquals(resumedPrefix, protocol.execute(request.copy(continuation = token), owner.authority, budget))
            store.commit(publication.claim, resumedPrefix).refined()
            val final =
                protocol.execute(request.copy(continuation = successor), owner.authority, budget)
                    as OperationOutcome.Complete
            store.commit(publication.claim, final).refined()
            assertEquals(
                semantic.evidence.payload.diagnostics.values,
                prefix.evidence.payload.diagnostics.values +
                    resumedPrefix.evidence.payload.diagnostics.values +
                    final.evidence.payload.diagnostics.values,
            )
            assertEquals(1, scans)
        }
    }

    @Test
    fun `failed suffix retention leaves proven parent available for a new bounded attempt`() = runTest {
        with(fixture) {
            val store = DiagnosticCheckpointStore(capacity = 3)
            val publication = DiagnosticPublicationFixture.Capture()
            val protocol = protocol(store, publication) { completed() }
            val semantic = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            val suffix = semantic.dropFacts(1)
            val token = store.issueOutput(publication.claim, suffix, measure(suffix)).refined()
            store.commit(publication.claim, prefix(semantic, 1, token)).refined()
            assertEquals(suffix, protocol.execute(request.copy(continuation = token), owner.authority, budget))
            val last = suffix.dropFacts(1)
            // One advancing child fits after the inactive first-page cache is evicted. The active
            // parent, claim and this hidden child then occupy the entire shared three-entry quota.
            store.issueOutput(publication.claim, last, measure(last)).refined()
            val another = last.dropFacts(1)
            assertEquals(
                Refinement.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED),
                store.issueOutput(publication.claim, another, measure(another)),
            )
            store.discard(publication.claim)
            assertEquals(suffix, protocol.execute(request.copy(continuation = token), owner.authority, budget))
            store.discard(publication.claim)
        }
    }
}
