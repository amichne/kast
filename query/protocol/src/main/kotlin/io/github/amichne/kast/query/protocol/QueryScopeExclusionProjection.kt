package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.QueryScopeExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryScopeMembershipAuthorityDocument
import io.github.amichne.kast.protocol.contract.QueryWalkScopeExclusionDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.relation.contract.RelationScopeExclusion
import io.github.amichne.kast.traversal.contract.TraversalScopeExclusion

internal fun RelationScopeExclusion.projectScopeExclusion(
    authority: QueryReferenceAuthority
): QueryScopeExclusionDocument? {
    val candidate =
        when (
            val issued =
                authority.issueRangeCandidate(
                    basis,
                    occurrence.file,
                    occurrence.range.startInclusive,
                    occurrence.range.endExclusive,
                )
        ) {
            is CandidateSelectorTokenIssuance.Issued -> issued.selector
            is CandidateSelectorTokenIssuance.Rejected -> return null
        }
    val targetDocument = excludedTargetDocument() ?: return null
    return QueryScopeExclusionDocument(
        RelationOccurrenceDocument(
            candidate,
            ProtocolText.parse(occurrence.file.stableValue).scopeValueOrNull() ?: return null,
            occurrence.range.scopeRangeDocument() ?: return null,
        ),
        targetDocument,
        QueryScopeExclusionReasonDocument.valueOf(reason.name),
        QueryScopeMembershipAuthorityDocument.valueOf(membershipAuthority.name),
        requestedDomain.requestedDomainDocument(),
        relationDomainDocument(effectiveScope, effectiveConstraints) ?: return null,
        QueryRelationDomainFingerprint.parse(effectiveDomain.value).scopeValueOrNull() ?: return null,
    )
}

private fun RelationScopeExclusion.excludedTargetDocument(): QueryExcludedCompilerTargetDocument? {
    val compilerEvidence =
        CompilerSymbolEvidenceDocument.restore(
                ProtocolText.parse(target.compilerIdentity.value).scopeValueOrNull() ?: return null,
                target.signature.protocolDocument() ?: return null,
            )
            .scopeValueOrNull() ?: return null
    val targetDocument =
        QueryExcludedCompilerTargetDocument.create(
                ProtocolText.parse(target.file.stableValue).scopeValueOrNull() ?: return null,
                target.range.scopeRangeDocument() ?: return null,
                ProtocolText.parse(target.name.value).scopeValueOrNull() ?: return null,
                target.kind.protocolKind(),
                compilerEvidence,
            )
            .scopeValueOrNull() ?: return null
    return targetDocument
}

internal fun TraversalScopeExclusion.projectScopeExclusion(
    authority: QueryReferenceAuthority
): QueryWalkScopeExclusionDocument? {
    val subject =
        when (val issued = authority.issueEndpoint(entry.node.endpoint)) {
            is RelationEndpointIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
            is RelationEndpointIssuance.Rejected -> return null
        }
    return QueryWalkScopeExclusionDocument(
        subject,
        TraversalDepthDocument.parse(entry.depth.value).scopeValueOrNull() ?: return null,
        exclusion.projectScopeExclusion(authority) ?: return null,
    )
}

private fun io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange.scopeRangeDocument():
    SourceRangeDocument? =
    SourceRangeDocument.create(
            ProtocolOffset.parse(startInclusive).scopeValueOrNull() ?: return null,
            ProtocolOffset.parse(endExclusive).scopeValueOrNull() ?: return null,
        )
        .scopeValueOrNull()

private fun <Value, Failure> Refinement<Value, Failure>.scopeValueOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
