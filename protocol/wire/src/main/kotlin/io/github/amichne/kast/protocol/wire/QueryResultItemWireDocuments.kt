@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
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
}

@Serializable internal data class QueryExactLocationWireDocument(val file: String, val range: SourceRangeWireDocument)

internal fun QueryResultItemDocument.toWire(): QueryResultItemWireDocument =
    when (this) {
        is QueryResultItemDocument.ExactSymbol ->
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
    }

internal fun QueryResultItemWireDocument.toContract(): WireDocumentConversion<QueryResultItemDocument> =
    when (this) {
        is QueryResultItemWireDocument.ExactSymbol ->
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
