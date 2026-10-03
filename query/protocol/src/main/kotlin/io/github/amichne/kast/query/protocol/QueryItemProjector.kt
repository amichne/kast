package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.QueryBindingCellDocument
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QuerySourceWindowDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTextMatchDocument
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryBinding
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryBindingValue
import io.github.amichne.kast.query.contract.QueryOccurrence
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId

internal class QueryItemProjector(private val authority: QueryReferenceAuthority) {
    fun projectItems(
        output: QueryOutputDocument,
        rows: QueryRows,
        originalRowIds: List<QueryResultRowReference>? = null,
    ): QueryProjection<QueryResultItemDocument> {
        return when (rows) {
            is QueryRows.ImpactWitness -> projectWitnessRows(output, rows, originalRowIds)
            is QueryRows.Symbols -> projectSymbolRows(output, rows)
            is QueryRows.Occurrences ->
                if (output == QueryOutputDocument.Occurrences) rows.values.mapProjected(::projectOccurrence)
                else QueryProjection.Rejected
            is QueryRows.ValuePaths ->
                if (output == QueryOutputDocument.ValuePaths) projectValuePaths(rows) else QueryProjection.Rejected
            is QueryRows.Bindings ->
                if (output == QueryOutputDocument.BindingRows) rows.values.mapProjected(::projectBindingRow)
                else QueryProjection.Rejected
        }
    }

    private fun projectWitnessRows(
        output: QueryOutputDocument,
        rows: QueryRows.ImpactWitness,
        originalRowIds: List<QueryResultRowReference>?,
    ): QueryProjection<QueryResultItemDocument> =
        if (output is QueryOutputDocument.ImpactWitness && output.section.witnessSection() == rows.view.section)
            rows.projectWitnessItems(originalRowIds)
        else QueryProjection.Rejected

    private fun projectSymbolRows(
        output: QueryOutputDocument,
        rows: QueryRows.Symbols,
    ): QueryProjection<QueryResultItemDocument> =
        when (output) {
            is QueryOutputDocument.Symbols -> projectSymbols(output, rows.values)
            QueryOutputDocument.Occurrences -> projectOccurrences(rows.values)
            QueryOutputDocument.TraversalRecords -> projectTraversalRecords(rows.values)
            is QueryOutputDocument.ImpactWitness,
            QueryOutputDocument.BindingRows,
            QueryOutputDocument.ValuePaths -> QueryProjection.Rejected
        }

    private fun projectValuePaths(rows: QueryRows.ValuePaths): QueryProjection<QueryResultItemDocument> {
        val documents = mutableListOf<QueryResultItemDocument>()
        for (path in rows.values) when (val projected = path.impactDocument()) {
            is io.github.amichne.kast.kernel.Refinement.Refined ->
                documents += QueryResultItemDocument.ValuePath(projected.value)
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                return QueryProjection.ImpactRejected(projected.failure)
        }
        return QueryProjection.Projected(documents)
    }

    private fun projectOccurrence(value: QueryOccurrence): QueryResultItemDocument? {
        return when (value) {
            is QueryOccurrence.Reference -> {
                val document = value.value.protocolDocument(authority) ?: return null
                QueryResultItemDocument.ReferenceOccurrence(
                    QueryReferenceDocument.ExactSymbol(document.target.selector),
                    document,
                )
            }
            is QueryOccurrence.Declaration -> {
                val token =
                    when (val issued = authority.issueExact(value.symbol.selector)) {
                        is ExactSelectorIssuance.Issued -> issued.selector
                        is ExactSelectorIssuance.Rejected -> return null
                    }
                QueryResultItemDocument.Occurrence(
                    QueryReferenceDocument.ExactSymbol(token),
                    value.fact.protocolDocument(authority) ?: return null,
                )
            }
        }
    }

    private fun projectBindingRow(row: QueryBindingRow): QueryResultItemDocument.BindingRow? {
        val left = projectBinding(row.left) ?: return null
        val right = projectBinding(row.right) ?: return null
        return QueryResultItemDocument.BindingRow.create(left, right).refinedForQueryOrNull()
    }

    private fun projectBinding(binding: QueryBinding): QueryBindingCellDocument? {
        val name = QueryBindingNameDocument.parse(binding.name.value).refinedForQueryOrNull() ?: return null
        val symbol = projectBindingSymbol(binding.value.symbol) ?: return null
        return when (val value = binding.value) {
            is QueryBindingValue.Symbol -> QueryBindingCellDocument.Symbol(name, symbol)
            is QueryBindingValue.Occurrence ->
                QueryBindingCellDocument.Occurrence(
                    name,
                    symbol,
                    value.fact.protocolDocument(authority) ?: return null,
                )
        }
    }

    private fun projectBindingSymbol(symbol: QuerySymbol): QueryResultItemDocument.ExactSymbol? {
        val fields = buildList {
            add(QuerySymbolFieldDocument.NAME)
            add(QuerySymbolFieldDocument.LOCATION)
            add(QuerySymbolFieldDocument.SIGNATURE)
            if (symbol.source is QuerySymbolSource.Returned) add(QuerySymbolFieldDocument.SOURCE)
        }
        val bounded = BoundedProtocolList.create(fields).refinedForQueryOrNull() ?: return null
        return projectSymbol(QueryOutputDocument.Symbols(bounded), symbol)
    }

    private fun projectTraversalRecords(items: List<QuerySymbol>): QueryProjection.Legacy<QueryResultItemDocument> =
        items.mapProjected { symbol ->
            val record =
                (symbol.walkArrival as? QueryWalkArrival.Proven)?.records?.singleOrNull() ?: return@mapProjected null
            val token =
                when (val issued = authority.issueExact(symbol.selector)) {
                    is ExactSelectorIssuance.Issued -> issued.selector
                    is ExactSelectorIssuance.Rejected -> return@mapProjected null
                }
            val depth =
                TraversalDepthDocument.parse(record.depth.value).refinedForQueryOrNull() ?: return@mapProjected null
            val relation = record.fact.protocolDocument(authority) ?: return@mapProjected null
            QueryResultItemDocument.TraversalRecord(
                QueryReferenceDocument.ExactSymbol(token),
                TraversalRecordDocument(depth, relation),
            )
        }

    private fun projectOccurrences(items: List<QuerySymbol>): QueryProjection.Legacy<QueryResultItemDocument> =
        items.mapProjected { symbol ->
            val fact =
                (symbol.arrival as? QueryArrivalEvidence.Proven)?.facts?.singleOrNull() ?: return@mapProjected null
            val token =
                when (val issued = authority.issueExact(symbol.selector)) {
                    is ExactSelectorIssuance.Issued -> issued.selector
                    is ExactSelectorIssuance.Rejected -> return@mapProjected null
                }
            val relation = fact.protocolDocument(authority) ?: return@mapProjected null
            QueryResultItemDocument.Occurrence(QueryReferenceDocument.ExactSymbol(token), relation)
        }

    private fun projectSymbols(
        output: QueryOutputDocument.Symbols,
        items: List<QuerySymbol>,
    ): QueryProjection.Legacy<QueryResultItemDocument> = items.mapProjected { symbol -> projectSymbol(output, symbol) }

    private fun projectSymbol(
        output: QueryOutputDocument.Symbols,
        symbol: QuerySymbol,
    ): QueryResultItemDocument.ExactSymbol? {
        val token =
            when (val issued = authority.issueExact(symbol.selector)) {
                is ExactSelectorIssuance.Issued -> issued.selector
                is ExactSelectorIssuance.Rejected -> return null
            }
        val document = symbol.description.protocolDocument(token) ?: return null
        val connections = symbol.connections.mapProjected { it.protocolDocument(authority) }
        val boundedConnections =
            when (connections) {
                is QueryProjection.Projected ->
                    BoundedProtocolList.create(connections.values).refinedForQueryOrNull() ?: return null
                QueryProjection.Rejected -> return null
            }
        val source =
            when (val projected = projectSource(output, symbol.source)) {
                is SourceProjection.Projected -> projected.source
                SourceProjection.Rejected -> return null
            }
        val matches =
            when (val projected = projectTextMatches(symbol)) {
                is QueryProjection.Projected ->
                    (BoundedProtocolList.create(projected.values).refinedForQueryOrNull() ?: return null).takeIf {
                        it.values.isNotEmpty()
                    }
                QueryProjection.Rejected -> return null
            }
        return QueryResultItemDocument.ExactSymbol(
            ref = QueryReferenceDocument.ExactSymbol(token),
            kind = document.kind,
            name = document.name.takeIf { QuerySymbolFieldDocument.NAME in output.fields.values },
            location =
                QueryExactLocationDocument(document.file, document.range).takeIf {
                    QuerySymbolFieldDocument.LOCATION in output.fields.values
                },
            signature =
                document.compilerEvidence.signature.takeIf {
                    QuerySymbolFieldDocument.SIGNATURE in output.fields.values
                },
            connections = boundedConnections,
            symbolId =
                io.github.amichne.kast.protocol.contract.SymbolIdDocument.parse(
                        io.github.amichne.kast.symbol.contract.CanonicalSymbolId.from(symbol.selector).value
                    )
                    .refinedForQueryOrNull() ?: return null,
            source = source,
            matches = matches,
        )
    }

    private fun projectTextMatches(symbol: QuerySymbol): QueryProjection.Legacy<QueryTextMatchDocument> =
        symbol.textMatches.values.mapProjected {
            when (val match = it.protocolDocument()) {
                is QueryTextMatchProjection.Projected -> match.document
                QueryTextMatchProjection.ScalarContractViolation,
                is QueryTextMatchProjection.EvidenceContractViolation -> null
            }
        }

    private fun projectSource(output: QueryOutputDocument.Symbols, source: QuerySymbolSource): SourceProjection {
        if (QuerySymbolFieldDocument.SOURCE !in output.fields.values) return SourceProjection.Projected(null)
        return when (source) {
            is QuerySymbolSource.Returned -> {
                val text =
                    ProtocolSourceText.parse(source.value.text).refinedForQueryOrNull()
                        ?: return SourceProjection.Rejected
                val lines =
                    SourceLineRangeDocument.parse(
                            source.value.lines.startInclusive.value,
                            source.value.lines.endInclusive.value,
                        )
                        .refinedForQueryOrNull() ?: return SourceProjection.Rejected
                SourceProjection.Projected(QuerySourceWindowDocument(text, lines))
            }
            is QuerySymbolSource.Rejected,
            is QuerySymbolSource.Withheld -> SourceProjection.Projected(null)
            QuerySymbolSource.Pending -> SourceProjection.Rejected
        }
    }
}

private sealed interface SourceProjection {
    data class Projected(val source: QuerySourceWindowDocument?) : SourceProjection

    data object Rejected : SourceProjection
}
