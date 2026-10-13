package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySourceFailure
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Fifty-three exact relation occurrences are starting facts, independent of projected symbol fields. */
internal class RetainedSymbolGraphFixture {
    val retained = RetainedGraphDeliveryFixture()
    val selectedOutput = QueryOutputDocument.Symbols(graphFixtureList(emptyList()))
    val request =
        retained.request.copy(
            output = QueryOutputDocument.Symbols(graphFixtureList(listOf(QuerySymbolFieldDocument.NAME)))
        )
    val facts =
        retained.occurrences.map { occurrence ->
            val owner = occurrence.ownership as RelationReferenceOwnership.DeclarationOwned
            val request =
                RelationRequest.start(retained.source.selector, RelationMeaning.References, retained.source.budget)
            RelationFact.create(
                    request = request,
                    source = owner.declaration,
                    target = request.subject,
                    occurrence = occurrence.occurrence,
                    provenance = occurrence.provenance,
                )
                .graphFixtureValue()
        }
    val symbols = facts.map { fact ->
        val source = fact.source as io.github.amichne.kast.relation.contract.RelationEndpoint.Resolved
        val selector = SymbolSelector.issue(source.lease, source.scope, source.evidence, source.constraints)
        QuerySymbol(SymbolDescription.from(selector), listOf(fact), arrival = QueryArrivalEvidence.Proven.one(fact))
    }

    fun issue(qualified: Boolean = false, sourceUnavailable: Boolean = false): QueryResultIssuance.Issued {
        val selected =
            if (sourceUnavailable)
                symbols.map {
                    it.copy(source = QuerySymbolSource.Withheld(SourceTextWithheldReason.BYTE_LIMIT_REACHED))
                }
            else symbols
        val failures =
            if (sourceUnavailable)
                selected.map {
                    QueryItemFailure.Source(
                        it.selector,
                        QuerySourceFailure.Withheld(SourceTextWithheldReason.BYTE_LIMIT_REACHED),
                    )
                }
            else emptyList()
        val result = QueryResult(QueryRows.Symbols.of(selected), failures, referenceObservations = retained.occurrences)
        val count = QueryCount.parse(53).graphFixtureValue()
        val execution =
            if (qualified || sourceUnavailable)
                QueryExecutionResult.Qualified(
                    result,
                    QueryCoverage.Qualified.create(
                            count,
                            setOf(
                                if (sourceUnavailable) QueryLimitation.SOURCE_INCOMPLETE
                                else QueryLimitation.RELATION_INCOMPLETE
                            ),
                        )
                        .graphFixtureValue(),
                    QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
                )
            else QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(count))
        val snapshot = QueryRetainedResult.capture(retained.source.authority, execution).graphFixtureValue()
        return retained.store.issueResult(request, snapshot) as QueryResultIssuance.Issued
    }
}
