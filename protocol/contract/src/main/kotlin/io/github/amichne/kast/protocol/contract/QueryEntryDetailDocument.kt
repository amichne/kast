package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

@Serializable
enum class QueryEntryDetailSection {
    CANONICAL_ENTRY_JSON
}

@Serializable
enum class QueryEntryDetailTarget {
    ROW,
    EVIDENCE,
}

@Serializable
enum class QueryEntryDetailKind {
    EXACT_SYMBOL,
    OCCURRENCE,
    REFERENCE_OCCURRENCE,
    TRAVERSAL_RECORD,
    BINDING_ROW,
    VALUE_PATH,
    IMPACT_WITNESS,
    FAILURE,
    OMISSION,
    WALK_OBSERVATION,
    REFERENCE_OBSERVATION,
    DISCOVERY_OBSERVATION,
    RELATION_OBSERVATION,
}

/** A detail address is meaningful only under its original retained-result authority. */
@Serializable
data class QueryEntryDetailSelectorDocument(
    val result: QueryResultReference,
    val section: QueryEntryDetailSection,
    val target: QueryEntryDetailTarget,
    val ordinal: QueryResultCursor,
    val kind: QueryEntryDetailKind,
    @ProtocolStringConstraint(pattern = "^[0-9a-f]{64}$", minimumLength = 64, maximumLength = 64)
    val sha256: ProtocolText,
    val offset: ProtocolOffset,
)

/** Text is an opaque fragment of one canonical serialized entry, never a complete evidence claim. */
@Serializable
data class QueryEntryDetailDocument(
    val selector: QueryEntryDetailSelectorDocument,
    val end: ProtocolOffset,
    val total: ProtocolOffset,
    @ProtocolStringConstraint(maximumLength = 32768) val text: ProtocolText,
)

/** Identity and meaning stay bounded. The selector does not count as delivered evidence. */
@Serializable
data class QueryDeferredDetailDocument(
    val selector: QueryEntryDetailSelectorDocument,
    @ProtocolStringConstraint(maximumLength = 256) val meaning: ProtocolText,
)

fun QueryResultItemDocument.detailKind(): QueryEntryDetailKind =
    when (this) {
        is QueryResultItemDocument.ExactSymbol -> QueryEntryDetailKind.EXACT_SYMBOL
        is QueryResultItemDocument.Occurrence -> QueryEntryDetailKind.OCCURRENCE
        is QueryResultItemDocument.ReferenceOccurrence -> QueryEntryDetailKind.REFERENCE_OCCURRENCE
        is QueryResultItemDocument.TraversalRecord -> QueryEntryDetailKind.TRAVERSAL_RECORD
        is QueryResultItemDocument.BindingRow -> QueryEntryDetailKind.BINDING_ROW
        is QueryResultItemDocument.ValuePath -> QueryEntryDetailKind.VALUE_PATH
        is QueryResultItemDocument.ImpactWitness -> QueryEntryDetailKind.IMPACT_WITNESS
        is QueryResultItemDocument.DeferredDetail -> detail.selector.kind
    }
