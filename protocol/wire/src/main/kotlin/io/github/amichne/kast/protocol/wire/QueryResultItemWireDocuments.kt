@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryBindingCellDocument
import io.github.amichne.kast.protocol.contract.QueryBindingNameDocument
import io.github.amichne.kast.protocol.contract.QueryExactLocationDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface QueryResultItemWireDocument {
    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val kind: SymbolKindWireDocument,
        val name: String?,
        val location: QueryExactLocationWireDocument?,
        val signature: CompilerSignatureWireDocument?,
        val connections: List<RelationFactWireDocument>,
        val symbolId: String,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val source: QuerySourceWindowWireDocument? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val relation: RelationFactWireDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument

    @Serializable
    @SerialName("traversal_record")
    data class TraversalRecord(
        val ref: QueryReferenceWireDocument.ExactSymbol,
        val record: TraversalRecordWireDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument

    @Serializable
    @SerialName("binding_row")
    data class BindingRow(
        val left: QueryBindingCellWireDocument,
        val right: QueryBindingCellWireDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        val rowId: String? = null,
    ) : QueryResultItemWireDocument
}

@Serializable
internal sealed interface QueryBindingCellWireDocument {
    @Serializable
    @SerialName("symbol")
    data class Symbol(
        val name: QueryBindingNameDocument,
        val symbol: QueryResultItemWireDocument.ExactSymbol,
    ) : QueryBindingCellWireDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        val name: QueryBindingNameDocument,
        val symbol: QueryResultItemWireDocument.ExactSymbol,
        val relation: RelationFactWireDocument,
    ) : QueryBindingCellWireDocument
}

@Serializable internal data class QueryExactLocationWireDocument(val file: String, val range: SourceRangeWireDocument)

internal fun QueryResultItemDocument.toWire(): QueryResultItemWireDocument =
    when (this) {
        is QueryResultItemDocument.ExactSymbol -> toExactWire()
        is QueryResultItemDocument.Occurrence ->
            QueryResultItemWireDocument.Occurrence(
                QueryReferenceWireDocument.ExactSymbol(ref.token.value),
                relation.toWireDocument(),
                rowId?.value,
            )
        is QueryResultItemDocument.TraversalRecord ->
            QueryResultItemWireDocument.TraversalRecord(
                QueryReferenceWireDocument.ExactSymbol(ref.token.value),
                record.toWireDocument(),
                rowId?.value,
            )
        is QueryResultItemDocument.BindingRow ->
            QueryResultItemWireDocument.BindingRow(left.toWire(), right.toWire(), rowId?.value)
    }

private fun QueryResultItemDocument.ExactSymbol.toExactWire(): QueryResultItemWireDocument.ExactSymbol =
    QueryResultItemWireDocument.ExactSymbol(
        QueryReferenceWireDocument.ExactSymbol(ref.token.value),
        kind.toWireDocument(),
        name?.value,
        location?.let { QueryExactLocationWireDocument(it.file.value, it.range.toWireDocument()) },
        signature?.toWireDocument(),
        connections.values.map(RelationFactDocument::toWireDocument),
        symbolId.value,
        source?.let {
            QuerySourceWindowWireDocument(
                it.text.value,
                SourceLineRangeWireDocument(it.lines.startInclusive.value, it.lines.endInclusive.value),
            )
        },
        rowId?.value,
    )

private fun QueryBindingCellDocument.toWire(): QueryBindingCellWireDocument =
    when (this) {
        is QueryBindingCellDocument.Symbol -> QueryBindingCellWireDocument.Symbol(name, symbol.toExactWire())
        is QueryBindingCellDocument.Occurrence ->
            QueryBindingCellWireDocument.Occurrence(
                name,
                symbol.toExactWire(),
                relation.toWireDocument(),
            )
    }

internal fun QueryResultItemWireDocument.toContract(): WireDocumentConversion<QueryResultItemDocument> =
    when (this) {
        is QueryResultItemWireDocument.ExactSymbol -> toExactContract()
        is QueryResultItemWireDocument.Occurrence ->
            ref.token.queryItemText().flatMapConverted { token ->
                relation.toContract().flatMapConverted { fact ->
                    optionalRowId(rowId).mapConverted { id ->
                        QueryResultItemDocument.Occurrence(QueryReferenceDocument.ExactSymbol(token), fact, id)
                    }
                }
            }
        is QueryResultItemWireDocument.TraversalRecord ->
            ref.token.queryItemText().flatMapConverted { token ->
                record.toContract().flatMapConverted { record ->
                    optionalRowId(rowId).mapConverted { id ->
                        QueryResultItemDocument.TraversalRecord(QueryReferenceDocument.ExactSymbol(token), record, id)
                    }
                }
            }
        is QueryResultItemWireDocument.BindingRow ->
            combineConverted(left.toContract(), right.toContract(), optionalRowId(rowId)) { first, second, id ->
                    QueryResultItemDocument.BindingRow.create(first, second, id)
                }
                .flatMapConverted { it.toWireDocumentConversion() }
    }

private fun QueryBindingCellWireDocument.toContract(): WireDocumentConversion<QueryBindingCellDocument> =
    when (this) {
        is QueryBindingCellWireDocument.Symbol ->
            symbol.toExactContract().mapConverted { QueryBindingCellDocument.Symbol(name, it) }
        is QueryBindingCellWireDocument.Occurrence ->
            combineConverted(symbol.toExactContract(), relation.toContract()) { symbol, fact ->
                QueryBindingCellDocument.Occurrence(name, symbol, fact)
            }
    }

private fun QueryResultItemWireDocument.ExactSymbol.toExactContract():
    WireDocumentConversion<QueryResultItemDocument.ExactSymbol> =
    ref.token.queryItemText().flatMapConverted { token ->
        optionalText(name).flatMapConverted { projectedName ->
            location.toContract().flatMapConverted { projectedLocation ->
                optionalSignature(signature).flatMapConverted { projectedSignature ->
                    connections.convertEach(RelationFactWireDocument::toContract).flatMapConverted { facts ->
                        facts.queryItemBounded().flatMapConverted { boundedFacts ->
                            source.toContract().flatMapConverted { projectedSource ->
                                optionalRowId(rowId).flatMapConverted { projectedRowId ->
                                    io.github.amichne.kast.protocol.contract.SymbolIdDocument.parse(symbolId)
                                        .toWireDocumentConversion()
                                        .mapConverted { identity ->
                                            QueryResultItemDocument.ExactSymbol(
                                                QueryReferenceDocument.ExactSymbol(token),
                                                kind.toContract(),
                                                projectedName,
                                                projectedLocation,
                                                projectedSignature,
                                                boundedFacts,
                                                identity,
                                                projectedSource,
                                                projectedRowId,
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun QueryExactLocationWireDocument?.toContract(): WireDocumentConversion<QueryExactLocationDocument?> =
    if (this == null) WireDocumentConversion.Converted(null)
    else
        combineConverted(
            file.queryItemText(),
            range.toContract(),
            ::QueryExactLocationDocument,
        )

private fun optionalText(value: String?): WireDocumentConversion<ProtocolText?> =
    value?.queryItemText()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun optionalRowId(value: String?): WireDocumentConversion<QueryResultRowReference?> =
    value?.let { QueryResultRowReference.parse(it).toWireDocumentConversion() }
        ?: WireDocumentConversion.Converted(null)

private fun optionalSignature(
    value: CompilerSignatureWireDocument?
): WireDocumentConversion<CompilerSignatureDocument?> =
    value?.toContract()?.mapConverted { it } ?: WireDocumentConversion.Converted(null)

private fun String.queryItemText() = ProtocolText.parse(this).toWireDocumentConversion()

private fun <Value> List<Value>.queryItemBounded() = BoundedProtocolList.create(this).toWireDocumentConversion()
