package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationOmissionDocument
import io.github.amichne.kast.protocol.contract.QuerySourceFailureDocument
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal fun QueryRelationOmission.projectIssue(authority: QueryReferenceAuthority): QueryRelationOmissionDocument? =
    QueryRelationOmissionDocument(
        subject.exactReference(authority) ?: return null,
        meaning.protocolDocument(),
        evidence.protocolDocument() ?: return null,
    )

internal fun QueryItemFailure.projectIssue(authority: QueryReferenceAuthority): QueryItemFailureDocument? =
    when (this) {
        is QueryItemFailure.Refinement -> {
            val token =
                (authority.issueDeclarationCandidate(candidate) as? CandidateSelectorTokenIssuance.Issued)?.selector
                    ?: return null
            QueryItemFailureDocument.Refinement(
                QueryReferenceDocument.DeclarationCandidate(token),
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
    }

private fun SymbolSelector.exactReference(authority: QueryReferenceAuthority): QueryReferenceDocument.ExactSymbol? =
    when (val issued = authority.issueExact(this)) {
        is ExactSelectorIssuance.Issued -> QueryReferenceDocument.ExactSymbol(issued.selector)
        is ExactSelectorIssuance.Rejected -> null
    }
