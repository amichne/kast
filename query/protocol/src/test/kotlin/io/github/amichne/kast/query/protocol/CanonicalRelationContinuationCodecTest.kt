package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocumentFailure
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Traversal checkpoints still embed the relation-domain continuation codec. */
class CanonicalRelationContinuationCodecTest {
    @Test
    fun `relation continuation preserves consumed prefix and issuing authority`() = runTest {
        for ((fixture, version) in
            listOf(RelationPagingFixture.published() to "v1", RelationPagingFixture.live() to "v2")) {
            val first = fixture.firstPage() as RelationReadResult.Qualified
            val continuation = (first.coverage as RelationIncompleteCoverage.Resumable).continuation
            val document = CanonicalRelationContinuationCodec.encode(continuation)!!
            assertTrue(document.value.startsWith("relation-continuation:$version:"))
            val decoded =
                (CanonicalRelationContinuationCodec.decode(document, fixture.authority)
                        as CanonicalRelationContinuationDecoding.Decoded)
                    .continuation
            assertEquals(3L, decoded.nextProviderCursor.nextPosition.value)
            assertEquals(document, CanonicalRelationContinuationCodec.encode(decoded))
            assertEquals(listOf(0L, 1L, 2L), fixture.consumed)

            val otherAuthority =
                when (val current = fixture.authority) {
                    is SemanticReadLease ->
                        SemanticReadLease(current.workspaceRoot, EvidenceGeneration.parse(8).refined())
                    is LiveSemanticReadAuthority ->
                        LiveReadAuthorityFixture.create(
                            current.workspaceRoot,
                            UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        )
                }
            assertEquals(
                CanonicalRelationContinuationDecoding.AuthorityMismatch,
                CanonicalRelationContinuationCodec.decode(document, otherAuthority),
            )
            val tampered = document.value.dropLast(1) + if (document.value.last() == '0') "1" else "0"
            assertEquals(
                RelationContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
                (RelationContinuationDocument.parse(tampered) as Refinement.Rejected).failure,
            )
            assertEquals(
                RelationContinuationDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                (RelationContinuationDocument.parse("relation-output:v1:00000000-0000-0000-0000-000000000001")
                        as Refinement.Rejected)
                    .failure,
            )
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
