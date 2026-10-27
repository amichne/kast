package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSection
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryEvidenceWindowDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationUnitCount
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization

internal fun entryDigest(text: String): String = QueryEntryDetailSerialization.digest(text)

/** The result reference proves successful retention before this adapter publishes a detail address. */
internal fun QueryRunResult.deferredRow(index: Int): QueryRunResult {
    val retained = retention as? QueryResultRetention.Retained ?: return this
    val row = items.values[index]
    if (row is QueryResultItemDocument.DeferredDetail) return this
    val ordinal = (presentationWindow?.start?.value ?: 0) + index
    val summary = deferredRowSummary(row, retained.reference, ordinal)
    return copy(
        items =
            BoundedProtocolList.create(
                    items.values.mapIndexed { position, item ->
                        if (position == index) summary else item
                    }
                )
                .detailValue()
    )
}

internal fun deferredRowSummary(
    row: QueryResultItemDocument,
    result: QueryResultReference,
    ordinal: Int,
): QueryResultItemDocument.DeferredDetail {
    val entry = QueryEntryDetailSerialization.row(row)
    val selector =
        QueryEntryDetailSelectorDocument(
            result,
            QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
            QueryEntryDetailTarget.ROW,
            QueryResultCursor.parse(ordinal).detailValue(),
            entry.kind,
            ProtocolText.parse(entryDigest(entry.text)).detailValue(),
            ProtocolOffset.parse(0).detailValue(),
        )
    val summary = QueryDeferredDetailDocument(selector, ProtocolText.parse(entry.meaning.take(256)).detailValue())
    return QueryResultItemDocument.DeferredDetail(summary, row.rowId)
}

/** A smaller rejected preview keeps the same policy rejection and its retained authority. */
internal fun fitHostedRejectedEntryPreview(
    semantic: OperationOutcome.Rejected<QueryRunFailure>,
    completion: QueryRunRejection.CompletionUnproven,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
): Pair<HostedQueryOutcome, HostedResponse> {
    val retained =
        completion.evidence as? QueryCompletionEvidenceDocument.Retained
            ?: return semantic to
                HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, semantic, limits, maximumBytes)
    var rows = retained.preview.values
    fun encode(selected: List<QueryResultItemDocument>): Pair<HostedQueryOutcome, HostedResponse> {
        val reason =
            completion.copy(evidence = retained.copy(preview = BoundedProtocolList.create(selected).detailValue()))
        val failure =
            when (val original = semantic.reason) {
                is AdmittedQueryRunRejection -> AdmittedQueryRunRejection(reason, original.executionBudget)
                is QueryRunRejection -> reason
            }
        val outcome = OperationOutcome.Rejected(failure)
        return outcome to
            HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, outcome, limits, maximumBytes)
    }
    rows = rows.mapIndexed { ordinal, row ->
        if (
            row !is QueryResultItemDocument.DeferredDetail &&
                QueryEntryDetailSerialization.row(row).text.toByteArray(Charsets.UTF_8).size > maximumBytes.value
        )
            deferredRowSummary(row, retained.result, ordinal)
        else row
    }
    if (
        rows.isNotEmpty() &&
            encode(rows.take(1)).second is HostedResponse.Oversized &&
            rows.first() !is QueryResultItemDocument.DeferredDetail
    )
        rows = listOf(deferredRowSummary(rows.first(), retained.result, 0)) + rows.drop(1)
    var lower = 0
    var upper = rows.size
    var best = encode(emptyList())
    while (lower <= upper) {
        val count = lower + (upper - lower) / 2
        val candidate = encode(rows.take(count))
        if (candidate.second is HostedResponse.Canonical<*, *, *>) {
            best = candidate
            lower = count + 1
        } else upper = count - 1
    }
    return best
}

internal fun QueryRunResult.deferredEvidence(index: Int, entry: QueryEntryDetailSerialization.Entry): QueryRunResult {
    val retained = retention as? QueryResultRetention.Retained ?: return this
    val start = evidenceWindow?.start?.value ?: 0
    val deferredOrdinals = deferredEvidence.values.map { it.selector.ordinal.value }.toSet()
    var ordinal = start
    repeat(index + 1) {
        while (ordinal in deferredOrdinals) ordinal++
        if (it < index) ordinal++
    }
    val selector =
        QueryEntryDetailSelectorDocument(
            retained.reference,
            QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
            QueryEntryDetailTarget.EVIDENCE,
            QueryResultCursor.parse(ordinal).detailValue(),
            entry.kind,
            ProtocolText.parse(entryDigest(entry.text)).detailValue(),
            ProtocolOffset.parse(0).detailValue(),
        )
    val summary = QueryDeferredDetailDocument(selector, ProtocolText.parse(entry.meaning).detailValue())
    var offset = 0
    fun <T> remove(values: BoundedProtocolList<T>): BoundedProtocolList<T> {
        val selected = values.values.filterIndexed { local, _ -> local + offset != index }
        offset += values.values.size
        return BoundedProtocolList.create(selected).detailValue()
    }
    val window =
        evidenceWindow
            ?: QueryEvidenceWindowDocument.create(
                    QueryEvidenceCursor.Start,
                    QueryEvidenceCursor.parse(presentationUnitCount - items.values.size).detailValue(),
                    QueryEvidenceCursor.parse(presentationUnitCount - items.values.size).detailValue(),
                )
                .detailValue()
    return copy(
        failures = remove(failures),
        omissions = remove(omissions),
        walkObservations = remove(walkObservations),
        referenceObservations = remove(referenceObservations),
        discoveryObservations = remove(discoveryObservations),
        relationObservations = remove(relationObservations),
        deferredEvidence =
            BoundedProtocolList.create((deferredEvidence.values + summary).sortedBy { it.selector.ordinal.value })
                .detailValue(),
        evidenceWindow =
            QueryEvidenceWindowDocument.create(window.start, window.end, window.total, window.deferredCount + 1)
                .detailValue(),
    )
}

internal fun <Value, Failure> Refinement<Value, Failure>.detailValue(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted retained detail lost its constrained value: $failure")
    }
