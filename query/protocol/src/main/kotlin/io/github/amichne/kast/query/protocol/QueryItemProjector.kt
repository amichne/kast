package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.QueryBindingCellDocument
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QuerySourceWindowDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalRecordDocument
import io.github.amichne.kast.query.contract.QueryArrivalEvidence
import io.github.amichne.kast.query.contract.QueryBinding
import io.github.amichne.kast.query.contract.QueryBindingRow
import io.github.amichne.kast.query.contract.QueryBindingValue
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryWalkArrival
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId

internal class QueryItemProjector(private val authority: QueryReferenceAuthority) {
    fun projectItems(output: QueryOutputDocument, rows: QueryRows): QueryProjection<QueryResultItemDocument> =
        when (rows) {
            is QueryRows.Symbols ->
                when (output) {
                    is QueryOutputDocument.Symbols -> projectSymbols(output, rows.values)
                    QueryOutputDocument.Occurrences -> projectOccurrences(rows.values)
                    QueryOutputDocument.TraversalRecords -> projectTraversalRecords(rows.values)
                    QueryOutputDocument.BindingRows -> QueryProjection.Rejected
                }
            is QueryRows.Bindings ->
                if (output == QueryOutputDocument.BindingRows) rows.values.mapProjected(::projectBindingRow)
                else QueryProjection.Rejected
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

    private fun projectTraversalRecords(items: List<QuerySymbol>): QueryProjection<QueryResultItemDocument> =
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

    private fun projectOccurrences(items: List<QuerySymbol>): QueryProjection<QueryResultItemDocument> =
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
    ): QueryProjection<QueryResultItemDocument> = items.mapProjected { symbol -> projectSymbol(output, symbol) }

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
        )
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
