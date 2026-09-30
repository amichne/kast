package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticNonAdvancingPublicationTest {
    @Test
    fun `unchanged successor rejects with precise evidence and keeps the original parent available`() = runTest {
        val fixture = DiagnosticPublicationFixture()
        with(fixture) {
            val store = DiagnosticCheckpointStore()
            val publication = DiagnosticPublicationFixture.Capture()
            val protocol = protocol(store, publication) { completed() }
            val semantic = protocol.execute(request, owner.authority, budget) as OperationOutcome.Complete
            val suffix = semantic.dropFacts(1)
            val token = store.issueOutput(publication.claim, suffix, measure(suffix)).refined()
            store.commit(publication.claim, prefix(semantic, 1, token)).refined()
            val resumedRequest = request.copy(continuation = token)
            val restored = protocol.execute(resumedRequest, owner.authority, budget) as OperationOutcome.Complete
            assertEquals(
                Refinement.Rejected(DiagnosticPublicationFailure.NON_ADVANCING_SUCCESSOR),
                store.commit(publication.claim, prefix(restored, 1, token)),
            )
            store.discard(publication.claim)
            assertEquals(suffix, protocol.execute(resumedRequest, owner.authority, budget))
            store.discard(publication.claim)
        }
    }
}
