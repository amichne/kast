package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal fun restoreImpactDeclaration(
    authority: QueryReferenceAuthority,
    token: ProtocolText,
    lease: SemanticReadAuthority,
    position: Int,
): Refinement<SymbolSelector, QueryRunRejection> =
    when (val restored = authority.restoreExact(token, lease)) {
        is CanonicalSelectorDecoding.Decoded -> Refinement.Refined(restored.value)
        is CanonicalSelectorDecoding.Rejected ->
            Refinement.Rejected(
                QueryRunRejection.ImpactSourceRejected(
                    QueryImpactSourceFailureDocument.Reference.fromReference(
                        restored.queryReferenceRejection(position, token)
                    )
                )
            )
    }

internal fun matchImpactDeclaration(
    declaration: QueryImpactDeclarationDocument,
    selected: SymbolSelector,
    endpoint: RelationEndpoint,
    lease: SemanticReadAuthority,
    position: Int,
): Refinement<Unit, QueryRunRejection> {
    if (
        endpoint.compilerIdentity != selected.compilerIdentity ||
            endpoint.file != selected.file ||
            endpoint.range != selected.range
    )
        return impactFailure(QueryImpactSourceFailureCode.DECLARATION_MISMATCH, position)
    if (!declaration.declaration.matchesDeclaration(endpoint))
        return impactFailure(QueryImpactSourceFailureCode.DECLARATION_MISMATCH, position)
    if (endpoint.lease.identity != lease.identity || !declaration.declaration.basis.matchesBasis(lease.identity))
        return impactFailure(QueryImpactSourceFailureCode.STALE_DECLARATION, position)
    return Refinement.Refined(Unit)
}

internal fun QueryImpactProducerDocument.admitProducerAnchor(
    enclosing: SymbolSelector,
    authority: QueryReferenceAuthority,
    position: Int,
): Refinement<ExactDeclarationTextRange, QueryRunRejection> {
    val anchor =
        when (val parsed = ExactDeclarationTextRange.parse(this.anchor.start.value, this.anchor.end.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return impactFailure(QueryImpactSourceFailureCode.INVALID_ANCHOR, position)
        }
    candidateRef?.let { reference ->
        val restored = authority.restoreCandidate(reference, enclosing.lease)
        val selected =
            (restored as? CanonicalSelectorDecoding.Decoded)?.value as? CandidateSelector.Range
                ?: return impactFailure(QueryImpactSourceFailureCode.STALE_REQUESTED_SITE, position)
        if (
            selected.file != enclosing.file ||
                selected.startInclusive.value != anchor.startInclusive ||
                selected.endExclusive.value != anchor.endExclusive
        )
            return impactFailure(QueryImpactSourceFailureCode.ANCHOR_MISMATCH, position)
    }
    return Refinement.Refined(anchor)
}
