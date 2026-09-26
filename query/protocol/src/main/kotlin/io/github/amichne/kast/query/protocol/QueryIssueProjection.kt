package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRefinementLocationDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryWalkFailureDocument
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.traversal.contract.TraversalRejection

internal fun QueryRelationOmission.projectIssue(authority: QueryReferenceAuthority): QueryRelationOmissionDocument? =
    QueryRelationOmissionDocument(
        subject.exactReference(authority) ?: return null,
        meaning.protocolDocument(),
        evidence.protocolDocument() ?: return null,
    )

internal fun QueryItemFailure.projectIssue(authority: QueryReferenceAuthority): QueryItemFailureDocument? =
    when (this) {
        is QueryItemFailure.Refinement -> {
            val location = candidate.candidate.location as? SymbolDiscoveryCandidateLocation.Declaration ?: return null
            val file = (ProtocolText.parse(location.file.stableValue) as? io.github.amichne.kast.kernel.Refinement.Refined)?.value ?: return null
            val offset = (ProtocolOffset.parse(location.offset.value) as? io.github.amichne.kast.kernel.Refinement.Refined)?.value ?: return null
            QueryItemFailureDocument.Refinement(
                QueryRefinementLocationDocument(file, offset),
                QueryExactFailureDocument.valueOf(reason.name),
            )
        }
        is QueryItemFailure.ExactReference ->
            QueryItemFailureDocument.ExactReference(
                selector.exactReference(authority) ?: return null,
                QueryExactFailureDocument.valueOf(reason.name),
            )
        is QueryItemFailure.Visibility ->
            QueryItemFailureDocument.Predicate(
                selector.exactReference(authority) ?: return null,
                QueryPredicateFailureDocument.valueOf(reason.name),
            )
        is QueryItemFailure.PredicateUnproven ->
            QueryItemFailureDocument.Predicate(
                selector.exactReference(authority) ?: return null,
                QueryPredicateFailureDocument.PREDICATE_UNPROVEN,
            )
        is QueryItemFailure.Source ->
            QueryItemFailureDocument.Source(
                selector.exactReference(authority) ?: return null,
                QuerySourceFailureDocument.valueOf(
                    when (val cause = reason) {
                        is QuerySourceFailure.Rejected -> cause.reason.name
                        is QuerySourceFailure.Withheld -> cause.reason.name
                    }
                ),
            )
        is QueryItemFailure.Relation ->
            QueryItemFailureDocument.Relation(
                selector.exactReference(authority) ?: return null,
                meaning.protocolDocument(),
                QueryRelationFailureDocument.valueOf(reason.name),
            )
        is QueryItemFailure.Walk ->
            QueryItemFailureDocument.Walk(
                selector.exactReference(authority) ?: return null,
                meaning.protocolDocument(),
                reason.walkFailure(),
            )
    }

private fun TraversalRejection.walkFailure(): QueryWalkFailureDocument =
    when (this) {
        is TraversalRejection.OneHopRejected ->
            QueryWalkFailureDocument.OneHop(QueryRelationFailureDocument.valueOf(reason.name))
        TraversalRejection.RequiredEvidenceUnavailable -> QueryWalkFailureDocument.RequiredEvidenceUnavailable
        TraversalRejection.RequiredEvidenceStale -> QueryWalkFailureDocument.RequiredEvidenceStale
        TraversalRejection.ReaderContractViolation -> QueryWalkFailureDocument.ReaderContractViolation
        TraversalRejection.TraversalContractViolation -> QueryWalkFailureDocument.TraversalContractViolation
    }

private fun SymbolSelector.exactReference(authority: QueryReferenceAuthority): QueryReferenceDocument.ExactSymbol? =
    when (val issued = authority.issueExact(this)) {
        is ExactSelectorIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
        is ExactSelectorIssuance.Rejected -> null
    }
