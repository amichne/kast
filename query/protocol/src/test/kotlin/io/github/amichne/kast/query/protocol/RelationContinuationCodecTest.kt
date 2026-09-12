package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelationContinuationCodecTest {
    @Test
    fun `published and live owner issued pages resume after the exact consumed prefix`() = runTest {
        for ((fixture, version) in
            listOf(RelationPagingFixture.published() to "v1", RelationPagingFixture.live() to "v2")) {
            val first = fixture.page() as OperationOutcome.Qualified
            val continuation = (first.qualification as RelationReadQualification.Resumable).continuation
            assertTrue(continuation.value.startsWith("relation-continuation:$version:"))
            val decoded =
                (CanonicalRelationContinuationCodec.decode(continuation, fixture.authority)
                        as CanonicalRelationContinuationDecoding.Decoded)
                    .continuation
            assertEquals(3L, decoded.nextProviderCursor.nextPosition.value)
            assertEquals(continuation, CanonicalRelationContinuationCodec.encode(decoded))
            val second = fixture.page(RelationReadPositionDocument.Resume(continuation)) as OperationOutcome.Complete
            assertEquals(3, first.evidence.payload.relations.values.size)
            assertEquals(1, second.evidence.payload.relations.values.size)
            assertEquals(
                listOf(10, 12, 14),
                first.evidence.payload.relations.values.map { it.occurrence.range.startInclusive.value },
            )
            assertEquals(16, second.evidence.payload.relations.values.single().occurrence.range.startInclusive.value)
            assertEquals(listOf(0L, 1L, 2L, 3L), fixture.consumed)
        }
    }

    @Test
    fun `terminal incomplete projection exposes no continuation`() = runTest {
        val fixture = RelationPagingFixture.live()
        val protocol = CanonicalRelationReadProtocol(RelationOperations(::terminalIncomplete), fixture.references)
        val outcome =
            protocol.execute(fixture.request(RelationReadPositionDocument.Start), fixture.authority, fixture.budget)
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, outcome)
        assertInstanceOf(RelationReadQualification.TerminalIncomplete::class.java, qualified.qualification)
    }

    private fun terminalIncomplete(request: DomainRelationRequest): RelationReadResult {
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0L).refined(),
                    RelationWorkCount.parse(0L).refined(),
                    RelationResultCount.parse(0).refined(),
                )
                .refined()
        val qualified =
            RelationCompilation.qualifiedTerminal(
                    batch,
                    setOf(RelationLimitation.UNRESOLVED_TARGET),
                )
                .refined()
        return RelationReadResult.Qualified(batch, qualified.coverage)
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
        }
}
