package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedTraversalResponseTest {
    @Test
    fun `oversized terminal traversal retains an ordered suffix and every partial expansion`() = runTest {
        val original = traversalFixture()
        val full =
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.traversalRun, original)
                as HostedResponse.Canonical<*, *, *>
        val maximum = ReturnedByteLimit.parse(full.document.toByteArray().size.toLong() - 1).proven()
        val retained = mutableListOf<HostedTraversalOutcome>()
        val response =
            encodeHostedTraversalResponse(original, ReadLimits.Default, ResultLimit.parse(3).proven(), maximum) {
                retained += it
                HostedOutputRetention.Retained(
                    ProtocolText.parse("traversal-output:v1:00000000-0000-0000-0000-000000000001").proven()
                )
            }
        assertTrue(response is HostedResponse.Canonical<*, *, *>, "Expected fitting traversal prefix, got $response")
        assertTrue(response.document.toByteArray().size <= maximum.value)
        val page =
            ((response as HostedResponse.Canonical<*, *, *>).semantic as OperationOutcome.Qualified).evidence.payload
                as TraversalRunResult
        val suffix = retained.single() as OperationOutcome.Qualified
        val remaining = suffix.evidence.payload as TraversalRunResult
        assertTrue(page.records.values.isNotEmpty())
        assertEquals(original.evidence.payload.records.values, page.records.values + remaining.records.values)
        assertEquals(original.evidence.payload.partialExpansions, page.partialExpansions)
        assertEquals(original.evidence.payload.partialExpansions, remaining.partialExpansions)
        assertEquals(original.evidence.payload.progress, page.progress)
        assertEquals(original.qualification, suffix.qualification)
    }

    private suspend fun traversalFixture(): OperationOutcome.Qualified<TraversalRunResult, TraversalRunQualification> {
        val owner = RelationPagingFixture.live()
        val relation = owner.page() as OperationOutcome.Qualified
        val records =
            relation.evidence.payload.relations.values.map {
                TraversalRecordDocument(TraversalDepthDocument.parse(1).proven(), it)
            }
        val partial =
            TraversalPartialExpansionDocument.create(
                    owner.exact,
                    TraversalDepthDocument.parse(0).proven(),
                    listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                    TraversalExpansionRemainderDocument.NOT_EXPLORED,
                )
                .proven()
        val result =
            TraversalRunResult(
                ProtocolText.parse(owner.authority.workspaceRoot.value).proven(),
                BoundedProtocolList.create(records).proven(),
                TraversalProgressDocument(1, 1, records.size.toLong(), 1),
                TraversalStrategyDocument.BreadthFirst,
                BoundedProtocolList.create(listOf(partial)).proven(),
            )
        val qualification =
            TraversalRunQualification.terminalIncomplete(
                    listOf(
                        TraversalLimitationDocument.DEPTH_LIMIT_REACHED,
                        TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
                    ),
                    listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                )
                .proven()
        return OperationOutcome.Qualified(
            EvidenceEnvelope(CanonicalOperation.TRAVERSAL_RUN.id, relation.evidence.basis, result),
            qualification,
        )
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
