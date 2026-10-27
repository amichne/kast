package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryWalkFailureDocument
import io.github.amichne.kast.protocol.contract.detailKind
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.KSerializer

/** Canonical entry serialization has one owner shared by summaries and exact detail reads. */
object QueryEntryDetailSerialization {
    fun digest(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    data class Entry(val text: String, val kind: QueryEntryDetailKind, val meaning: String)

    fun row(value: QueryResultItemDocument): Entry =
        entry(
            QueryResultItemWireDocument.serializer(),
            value.toWire(),
            value.detailKind(),
            when (value) {
                is QueryResultItemDocument.ExactSymbol ->
                    value.kind.name + " " + (value.name?.value?.detailLabel() ?: "")
                is QueryResultItemDocument.ImpactWitness ->
                    value.item.section.name + " ordinal " + value.item.ordinal.value
                is QueryResultItemDocument.DeferredDetail -> value.detail.meaning.value
                else -> value.detailKind().name
            },
        )

    fun evidence(result: QueryRunResult, ordinal: Int): Refinement<Entry, QueryExecutionRejectionDocument> {
        var index = ordinal
        if (index < 0) return rejected()
        if (index < result.failures.values.size)
            return Refinement.Refined(
                entry(
                    QueryItemFailureWireDocument.serializer(),
                    result.failures.values[index].toWire(),
                    QueryEntryDetailKind.FAILURE,
                    result.failures.values[index].detailMeaning(),
                )
            )
        index -= result.failures.values.size
        if (index < result.omissions.values.size)
            return Refinement.Refined(
                entry(
                    QueryRelationOmissionWireDocument.serializer(),
                    result.omissions.values[index].toWire(),
                    QueryEntryDetailKind.OMISSION,
                    result.omissions.values[index].relation.name +
                        " " +
                        result.omissions.values[index].evidence.reason.name,
                )
            )
        index -= result.omissions.values.size
        if (index < result.walkObservations.values.size)
            return Refinement.Refined(
                entry(
                    QueryWalkObservationWireDocument.serializer(),
                    result.walkObservations.values[index].toWireDocument(),
                    QueryEntryDetailKind.WALK_OBSERVATION,
                    "Walk observation",
                )
            )
        index -= result.walkObservations.values.size
        return observationEvidence(result, index)
    }

    private fun observationEvidence(
        result: QueryRunResult,
        ordinal: Int,
    ): Refinement<Entry, QueryExecutionRejectionDocument> {
        var index = ordinal
        if (index < result.referenceObservations.values.size)
            return Refinement.Refined(
                entry(
                    RelationReferenceOccurrenceWireDocument.serializer(),
                    result.referenceObservations.values[index].toWireDocument(),
                    QueryEntryDetailKind.REFERENCE_OBSERVATION,
                    "Reference occurrence",
                )
            )
        index -= result.referenceObservations.values.size
        if (index < result.discoveryObservations.values.size)
            return Refinement.Refined(
                entry(
                    QueryDiscoveryObservationDocument.serializer(),
                    result.discoveryObservations.values[index],
                    QueryEntryDetailKind.DISCOVERY_OBSERVATION,
                    "Declaration discovery observation",
                )
            )
        index -= result.discoveryObservations.values.size
        if (index < result.relationObservations.values.size)
            return Refinement.Refined(
                entry(
                    QueryRelationObservationWireDocument.serializer(),
                    result.relationObservations.values[index].toWireDocument(),
                    QueryEntryDetailKind.RELATION_OBSERVATION,
                    "Relation observation",
                )
            )
        return rejected()
    }

    private fun <Value> entry(
        serializer: KSerializer<Value>,
        value: Value,
        kind: QueryEntryDetailKind,
        meaning: String,
    ) = Entry(wireJson.encodeToString(serializer, value), kind, meaning)

    private fun rejected() = Refinement.Rejected(QueryExecutionRejectionDocument.EVIDENCE_CURSOR_OUT_OF_RANGE)
}

private fun QueryItemFailureDocument.detailMeaning(): String =
    when (this) {
        is QueryItemFailureDocument.Refinement -> "REFINEMENT " + reason.name
        is QueryItemFailureDocument.ExactReference -> "EXACT_REFERENCE " + reason.name
        is QueryItemFailureDocument.Predicate -> "PREDICATE " + reason.name
        is QueryItemFailureDocument.Source -> "SOURCE " + reason.name
        is QueryItemFailureDocument.SourceEnumerationIncomplete -> "SOURCE_ENUMERATION_INCOMPLETE"
        is QueryItemFailureDocument.Relation -> relation.name + " " + reason.name
        is QueryItemFailureDocument.Walk ->
            relation.name +
                " " +
                when (val failure = reason) {
                    is QueryWalkFailureDocument.OneHop -> failure.reason.name
                    QueryWalkFailureDocument.RequiredEvidenceUnavailable -> "REQUIRED_EVIDENCE_UNAVAILABLE"
                    QueryWalkFailureDocument.RequiredEvidenceStale -> "REQUIRED_EVIDENCE_STALE"
                    QueryWalkFailureDocument.ReaderContractViolation -> "READER_CONTRACT_VIOLATION"
                    QueryWalkFailureDocument.TraversalContractViolation -> "TRAVERSAL_CONTRACT_VIOLATION"
                }
    }

private fun String.detailLabel(): String =
    substring(0, offsetByCodePoints(0, minOf(MAX_DETAIL_LABEL_CODE_POINTS, codePointCount(0, length))))

private const val MAX_DETAIL_LABEL_CODE_POINTS = 96
