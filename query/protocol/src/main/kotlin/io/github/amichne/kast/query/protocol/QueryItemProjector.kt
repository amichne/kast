package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolSourceText
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QuerySourceWindowDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SourceLineRangeDocument
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId

internal class QueryItemProjector(private val authority: QueryReferenceAuthority) {
    fun projectItems(output: QueryOutputDocument, items: List<QuerySymbol>): QueryProjection<QueryResultItemDocument> =
        when (output) {
            is QueryOutputDocument.Symbols -> projectSymbols(output, items)
        }

    private fun projectSymbols(
        output: QueryOutputDocument.Symbols,
        items: List<QuerySymbol>,
    ): QueryProjection<QueryResultItemDocument> = items.mapProjected { symbol ->
        val token =
            when (val issued = authority.issueExact(symbol.selector)) {
                is ExactSelectorIssuance.Issued -> issued.selector
                is ExactSelectorIssuance.Rejected -> return@mapProjected null
            }
        val document = symbol.description.protocolDocument(token) ?: return@mapProjected null
        val connections = symbol.connections.mapProjected { it.protocolDocument(authority) }
        val boundedConnections =
            when (connections) {
                is QueryProjection.Projected ->
                    BoundedProtocolList.create(connections.values).refinedForQueryOrNull() ?: return@mapProjected null
                QueryProjection.Rejected -> return@mapProjected null
            }
        val source =
            when (val projected = projectSource(output, symbol.source)) {
                is SourceProjection.Projected -> projected.source
                SourceProjection.Rejected -> return@mapProjected null
            }
        QueryResultItemDocument.ExactSymbol(
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
                    .refinedForQueryOrNull() ?: return@mapProjected null,
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
                            source.value.lines.startInclusive.value.toLong(),
                            source.value.lines.endInclusive.value.toLong(),
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
