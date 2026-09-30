@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.QueryBindingCellDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.RelationFactDocument
import io.github.amichne.kast.protocol.wire.RelationReferenceOccurrenceWireDocument
import io.github.amichne.kast.protocol.wire.SymbolKindWireDocument
import io.github.amichne.kast.protocol.wire.toWireDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface QueryResultItemCliDocument {
    @Serializable
    @SerialName("exact-symbol")
    data class ExactSymbol(
        @ProtocolStringConstraint(pattern = "^exact:v[2345]:") val ref: String,
        val kind: SymbolKindWireDocument,
        val name: String?,
        val location: QueryExactLocationCliDocument?,
        val signature: CompilerSignatureCliDocument?,
        val connections: List<RelationFactCliDocument>,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val source: QuerySourceWindowCliDocument? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = QueryResultRowReference.SERIALIZED_PATTERN)
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        val ref: String,
        val relation: RelationFactCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = QueryResultRowReference.SERIALIZED_PATTERN)
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("reference-occurrence")
    data class ReferenceOccurrence(
        val ref: String,
        val occurrence: RelationReferenceOccurrenceWireDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = QueryResultRowReference.SERIALIZED_PATTERN)
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("traversal_record")
    data class TraversalRecord(
        val ref: String,
        val record: QueryTraversalRecordCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = QueryResultRowReference.SERIALIZED_PATTERN)
        val rowId: String? = null,
    ) : QueryResultItemCliDocument

    @Serializable
    @SerialName("binding_row")
    data class BindingRow(
        val left: QueryBindingCellCliDocument,
        val right: QueryBindingCellCliDocument,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        @SerialName("row_id")
        @ProtocolStringConstraint(pattern = QueryResultRowReference.SERIALIZED_PATTERN)
        val rowId: String? = null,
    ) : QueryResultItemCliDocument
}

@Serializable
internal sealed interface QueryBindingCellCliDocument {
    @Serializable
    @SerialName("symbol")
    data class Symbol(
        @ProtocolStringConstraint(pattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$", maximumLength = 64) val name: String,
        val symbol: QueryResultItemCliDocument.ExactSymbol,
    ) : QueryBindingCellCliDocument

    @Serializable
    @SerialName("occurrence")
    data class Occurrence(
        @ProtocolStringConstraint(pattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$", maximumLength = 64) val name: String,
        val symbol: QueryResultItemCliDocument.ExactSymbol,
        val relation: RelationFactCliDocument,
    ) : QueryBindingCellCliDocument
}

@Serializable
internal data class QuerySourceWindowCliDocument(
    val text: String,
    val startLine: Long,
    val endLine: Long,
)

@Serializable internal data class QueryExactLocationCliDocument(val file: String, val range: SourceRangeCliDocument)

internal fun QueryResultItemDocument.toCliDocument(): QueryResultItemCliDocument =
    when (this) {
        is QueryResultItemDocument.ExactSymbol -> toExactCliDocument()
        is QueryResultItemDocument.Occurrence ->
            QueryResultItemCliDocument.Occurrence(ref.toCliDocument(), relation.toCliDocument(), rowId?.value)
        is QueryResultItemDocument.ReferenceOccurrence ->
            QueryResultItemCliDocument.ReferenceOccurrence(
                ref.toCliDocument(),
                occurrence.toWireDocument(),
                rowId?.value,
            )
        is QueryResultItemDocument.TraversalRecord ->
            QueryResultItemCliDocument.TraversalRecord(ref.toCliDocument(), record.toQueryCliDocument(), rowId?.value)
        is QueryResultItemDocument.BindingRow ->
            QueryResultItemCliDocument.BindingRow(left.toCliDocument(), right.toCliDocument(), rowId?.value)
    }

private fun QueryResultItemDocument.ExactSymbol.toExactCliDocument(): QueryResultItemCliDocument.ExactSymbol =
    QueryResultItemCliDocument.ExactSymbol(
        ref.toCliDocument(),
        kind.toWireDocument(),
        name?.value,
        location?.let {
            QueryExactLocationCliDocument(
                it.file.value,
                SourceRangeCliDocument(it.range.startInclusive.value, it.range.endExclusive.value),
            )
        },
        signature?.toCliDocument(),
        connections.values.map(RelationFactDocument::toCliDocument),
        source?.let {
            QuerySourceWindowCliDocument(
                it.text.value,
                it.lines.startInclusive.value,
                it.lines.endInclusive.value,
            )
        },
        rowId?.value,
    )

private fun QueryBindingCellDocument.toCliDocument(): QueryBindingCellCliDocument =
    when (this) {
        is QueryBindingCellDocument.Symbol ->
            QueryBindingCellCliDocument.Symbol(
                name.value,
                symbol.toExactCliDocument(),
            )
        is QueryBindingCellDocument.Occurrence ->
            QueryBindingCellCliDocument.Occurrence(
                name.value,
                symbol.toExactCliDocument(),
                relation.toCliDocument(),
            )
    }
