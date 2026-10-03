package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowDomainDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowReadPositionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest

internal fun RelationRequest.impactDocument(): ImpactProjected<ImpactFlowDomainDocument> {
    val domain =
        relationDomainDocument(searchScope, searchConstraints)
            ?: return Refinement.Rejected(ImpactPathProjectionFailure.DOMAIN_PROJECTION_REJECTED)
    return subject
        .impactDeclaration()
        .impactZip(
            QueryRelationDomainFingerprint.parse(scopeFingerprint.value)
                .impactFailure(ImpactPathProjectionFailure::DomainFingerprint)
        )
        .impactZip(
            ReturnedByteLimit.parse(budget.returnedBytes.value).impactFailure(ImpactPathProjectionFailure::Budget)
        )
        .impactZip(position.impactDocument())
        .impactMap { (request, position) ->
            ImpactFlowDomainDocument(
                request.first.first,
                meaning.protocolDocument(),
                boundary.requestedDomainDocument(),
                domain,
                request.first.second,
                ImpactFlowBudgetDocument(
                    budget.resources.elapsedTimeLimit,
                    budget.resources.workUnitLimit,
                    budget.resources.resultLimit,
                    request.second,
                ),
                position,
            )
        }
}

private fun RelationReadPosition.impactDocument(): ImpactProjected<ImpactFlowReadPositionDocument> =
    when (this) {
        RelationReadPosition.Start -> Refinement.Refined(ImpactFlowReadPositionDocument.Start)
        is RelationReadPosition.Resume -> {
            val cursor = continuation.nextProviderCursor
            QueryRelationDomainFingerprint.parse(continuation.fingerprint.value)
                .impactFailure(ImpactPathProjectionFailure::DomainFingerprint)
                .impactZip(
                    QueryDiscoveryCountDocument.parse(cursor.nextPosition.value)
                        .impactFailure(ImpactPathProjectionFailure::Count)
                )
                .impactZip(
                    QueryRelationDomainFingerprint.parse(cursor.consumedPrefixDigest.value)
                        .impactFailure(ImpactPathProjectionFailure::DomainFingerprint)
                )
                .impactZip(
                    continuation.retainedLimitations
                        .sortedBy { it.ordinal }
                        .map { RelationLimitationDocument.valueOf(it.name) }
                        .impactBounded()
                )
                .impactMap { (continuation, limitations) ->
                    ImpactFlowReadPositionDocument.Resume(
                        continuation.first.first,
                        RelationProviderDocument.valueOf(cursor.provider.name),
                        continuation.first.second,
                        continuation.second,
                        limitations,
                    )
                }
        }
    }
