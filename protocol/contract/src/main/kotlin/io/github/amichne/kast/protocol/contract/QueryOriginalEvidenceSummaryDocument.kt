package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Capture happens before presentation selection. Later copies preserve this original fact. */
@Serializable
sealed interface QueryEvidenceSummaryState {
    @Serializable @SerialName("UNCAPTURED") data object Uncaptured : QueryEvidenceSummaryState

    @Serializable
    @SerialName("CAPTURED")
    data class Captured(val summary: QueryOriginalEvidenceSummaryDocument) : QueryEvidenceSummaryState
}

@Serializable
enum class QueryEvidenceFailureKind {
    REFINEMENT,
    EXACT_REFERENCE,
    PREDICATE,
    SOURCE,
    SOURCE_ENUMERATION_INCOMPLETE,
    RELATION,
    WALK,
}

@Serializable
enum class QueryEvidenceWalkFailureKind {
    ONE_HOP,
    REQUIRED_EVIDENCE_UNAVAILABLE,
    REQUIRED_EVIDENCE_STALE,
    READER_CONTRACT_VIOLATION,
    TRAVERSAL_CONTRACT_VIOLATION,
}

/** Finite reason sets summarize original evidence. Counts do not certify delivery of its full detail. */
@Serializable
data class QueryOriginalEvidenceSummaryDocument(
    @SerialName("failure_count") val failureCount: ProtocolOffset,
    @SerialName("omission_count") val omissionCount: ProtocolOffset,
    @SerialName("evidence_count") val evidenceCount: ProtocolOffset,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("failure_kinds")
    val failureKinds: BoundedProtocolList<QueryEvidenceFailureKind> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("omission_reasons")
    val omissionReasons: BoundedProtocolList<RelationLimitationDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("refinement_reasons")
    val refinementReasons: BoundedProtocolList<QueryExactFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("exact_reasons")
    val exactReasons: BoundedProtocolList<QueryExactFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("predicate_reasons")
    val predicateReasons: BoundedProtocolList<QueryPredicateFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("source_reasons")
    val sourceReasons: BoundedProtocolList<QuerySourceFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("source_limitations")
    val sourceLimitations: BoundedProtocolList<SourceReadLimitationDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("relation_reasons")
    val relationReasons: BoundedProtocolList<QueryRelationFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("walk_one_hop_reasons")
    val walkOneHopReasons: BoundedProtocolList<QueryRelationFailureDocument> = finite(emptyList()),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("walk_reasons")
    val walkReasons: BoundedProtocolList<QueryEvidenceWalkFailureKind> = finite(emptyList()),
) {
    companion object {
        fun from(result: QueryRunResult): QueryOriginalEvidenceSummaryDocument {
            val failures = result.failures.values
            val walks = failures.filterIsInstance<QueryItemFailureDocument.Walk>().map { it.reason }
            return QueryOriginalEvidenceSummaryDocument(
                offset(failures.size),
                offset(result.omissions.values.size),
                offset(result.presentationUnitCount - result.items.values.size),
                finite(failures.map { it.summaryKind() }),
                finite(result.omissions.values.map { it.evidence.reason }),
                finite(failures.filterIsInstance<QueryItemFailureDocument.Refinement>().map { it.reason }),
                finite(failures.filterIsInstance<QueryItemFailureDocument.ExactReference>().map { it.reason }),
                finite(failures.filterIsInstance<QueryItemFailureDocument.Predicate>().map { it.reason }),
                finite(failures.filterIsInstance<QueryItemFailureDocument.Source>().map { it.reason }),
                finite(
                    failures.filterIsInstance<QueryItemFailureDocument.SourceEnumerationIncomplete>().flatMap {
                        it.qualification.limitations
                    }
                ),
                finite(failures.filterIsInstance<QueryItemFailureDocument.Relation>().map { it.reason }),
                finite(walks.filterIsInstance<QueryWalkFailureDocument.OneHop>().map { it.reason }),
                finite(walks.map { it.summaryKind() }),
            )
        }
    }
}

val QueryRunResult.originalEvidenceSummaryDocument: QueryOriginalEvidenceSummaryDocument?
    get() =
        when (val state = originalEvidenceSummary) {
            QueryEvidenceSummaryState.Uncaptured -> null
            is QueryEvidenceSummaryState.Captured -> state.summary
        }

private fun QueryItemFailureDocument.summaryKind(): QueryEvidenceFailureKind =
    when (this) {
        is QueryItemFailureDocument.Refinement -> QueryEvidenceFailureKind.REFINEMENT
        is QueryItemFailureDocument.ExactReference -> QueryEvidenceFailureKind.EXACT_REFERENCE
        is QueryItemFailureDocument.Predicate -> QueryEvidenceFailureKind.PREDICATE
        is QueryItemFailureDocument.Source -> QueryEvidenceFailureKind.SOURCE
        is QueryItemFailureDocument.SourceEnumerationIncomplete ->
            QueryEvidenceFailureKind.SOURCE_ENUMERATION_INCOMPLETE
        is QueryItemFailureDocument.Relation -> QueryEvidenceFailureKind.RELATION
        is QueryItemFailureDocument.Walk -> QueryEvidenceFailureKind.WALK
    }

private fun QueryWalkFailureDocument.summaryKind(): QueryEvidenceWalkFailureKind =
    when (this) {
        is QueryWalkFailureDocument.OneHop -> QueryEvidenceWalkFailureKind.ONE_HOP
        QueryWalkFailureDocument.RequiredEvidenceUnavailable ->
            QueryEvidenceWalkFailureKind.REQUIRED_EVIDENCE_UNAVAILABLE
        QueryWalkFailureDocument.RequiredEvidenceStale -> QueryEvidenceWalkFailureKind.REQUIRED_EVIDENCE_STALE
        QueryWalkFailureDocument.ReaderContractViolation -> QueryEvidenceWalkFailureKind.READER_CONTRACT_VIOLATION
        QueryWalkFailureDocument.TraversalContractViolation -> QueryEvidenceWalkFailureKind.TRAVERSAL_CONTRACT_VIOLATION
    }

private fun offset(value: Int): ProtocolOffset =
    when (val parsed = ProtocolOffset.parse(value)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A bounded evidence count lost its offset proof")
    }

private fun <T : Enum<T>> finite(values: List<T>): BoundedProtocolList<T> =
    when (val parsed = BoundedProtocolList.create(values.distinct().sortedBy { it.ordinal })) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("A closed enum set exceeded the protocol collection limit")
    }
