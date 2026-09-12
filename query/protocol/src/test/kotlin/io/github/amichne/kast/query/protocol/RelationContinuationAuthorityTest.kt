package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocumentFailure
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.relation.contract.RelationResumeFailure
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.workspace.contract.LiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationContinuationAuthorityTest {
    @Test
    fun `supported versions remain bound to the issuing authority and revision`() = runTest {
        for (fixture in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val first = fixture.page() as OperationOutcome.Qualified
            val token = (first.qualification as RelationReadQualification.Resumable).continuation
            val swapped =
                token.value.replaceFirst(
                    Regex(":v[12]:"),
                    if (token.value.startsWith("relation-continuation:v1:")) ":v2:" else ":v1:",
                )
            assertEquals(
                CanonicalRelationContinuationDecoding.AuthorityMismatch,
                CanonicalRelationContinuationCodec.decode(
                    RelationContinuationDocument.parse(swapped).refined(),
                    fixture.authority,
                ),
            )
            val otherAuthority =
                when (fixture.authority) {
                    is SemanticReadLease ->
                        SemanticReadLease(
                            fixture.authority.workspaceRoot,
                            EvidenceGeneration.parse(8).refined(),
                        )
                    is LiveSemanticReadAuthority ->
                        LiveReadAuthorityFixture.create(
                            fixture.authority.workspaceRoot,
                            UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        )
                }
            assertEquals(
                CanonicalRelationContinuationDecoding.AuthorityMismatch,
                CanonicalRelationContinuationCodec.decode(token, otherAuthority),
            )
            assertEquals(listOf(0L, 1L, 2L), fixture.consumed)
        }
    }

    @Test
    fun `resume owner rejects another subject meaning or scope before provider work`() = runTest {
        for (fixture in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val first = fixture.page() as OperationOutcome.Qualified
            val token = (first.qualification as RelationReadQualification.Resumable).continuation
            val position = RelationReadPositionDocument.Resume(token)
            val decoded =
                (CanonicalRelationContinuationCodec.decode(token, fixture.authority)
                        as CanonicalRelationContinuationDecoding.Decoded)
                    .continuation
            val mismatch =
                fixture.protocol.execute(
                    fixture.request(position).copy(relation = RelationKindDocument.CALLERS),
                    fixture.authority,
                    fixture.budget,
                )
            assertEquals(
                RelationReadRejection.CONTINUATION_RELATION_MISMATCH,
                (mismatch as OperationOutcome.Rejected).reason,
            )
            val otherSubject = RelationPagingFixture(fixture.authority, "other")
            assertEquals(
                RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH,
                (otherSubject.page(position) as OperationOutcome.Rejected).reason,
            )
            assertTrue(otherSubject.consumed.isEmpty())
            assertEquals(
                RelationResumeFailure.SCOPE_MISMATCH,
                (DomainRelationRequest.resume(
                        fixture.selector,
                        RelationMeaning.References,
                        fixture.budget,
                        decoded,
                        RelationSearchBoundary.WORKSPACE_EXPANSION,
                    ) as Refinement.Rejected)
                    .failure,
            )
            assertEquals(listOf(0L, 1L, 2L), fixture.consumed, "rejected resumes reached the provider")
        }
    }

    @Test
    fun `both continuation versions reject corrupt encoding digests and domain fingerprints`() = runTest {
        for (fixture in listOf(RelationPagingFixture.published(), RelationPagingFixture.live())) {
            val first = fixture.page() as OperationOutcome.Qualified
            val document = (first.qualification as RelationReadQualification.Resumable).continuation
            val parts = document.value.split(':')
            val tamperedDigest = document.value.dropLast(1) + if (document.value.last() == '0') "1" else "0"
            assertEquals(
                RelationContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
                (RelationContinuationDocument.parse(tamperedDigest) as Refinement.Rejected).failure,
            )
            assertEquals(
                RelationContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                (RelationContinuationDocument.parse("relation-continuation:${parts[1]}:!:${parts[3]}")
                        as Refinement.Rejected)
                    .failure,
            )
            val fields = Base64.getUrlDecoder().decode(parts[2]).decodeToString().split('\n')
            val invalidFingerprint = token((fields.dropLast(1) + "a".repeat(64)).joinToString("\n"), parts[1])
            assertEquals(
                CanonicalRelationContinuationDecoding.Malformed,
                CanonicalRelationContinuationCodec.decode(invalidFingerprint, fixture.authority),
            )
            val malformed = token("not-domain-fields", parts[1])
            assertEquals(
                RelationReadRejection.CONTINUATION_MALFORMED,
                (fixture.page(RelationReadPositionDocument.Resume(malformed)) as OperationOutcome.Rejected).reason,
            )
            assertEquals(listOf(0L, 1L, 2L), fixture.consumed)
        }
    }

    private fun token(payload: String, version: String): RelationContinuationDocument {
        val bytes = payload.toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return RelationContinuationDocument.parse("relation-continuation:$version:$encoded:$digest").refined()
    }

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
