package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExpansionRemainderDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.query.protocol.RelationPagingFixture

internal class HostedTraversalPagingFixture
private constructor(
    val owner: RelationPagingFixture,
    val request: TraversalRunRequest,
    val outcome: OperationOutcome.Qualified<TraversalRunResult, TraversalRunQualification>,
) {
    companion object {
        suspend fun create(): HostedTraversalPagingFixture {
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
            val request =
                TraversalRunRequest(
                    owner.exact,
                    RelationKindDocument.REFERENCES,
                    ProtocolCount.parse(2).proven(),
                    ProtocolCount.parse(3).proven(),
                )
            return HostedTraversalPagingFixture(
                owner,
                request,
                OperationOutcome.Qualified(
                    EvidenceEnvelope(CanonicalOperation.TRAVERSAL_RUN.id, relation.evidence.basis, result),
                    qualification,
                ),
            )
        }

        private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
    }
}
