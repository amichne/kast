package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryPresentationWindowFailure {
    data object DETAIL_SELECTOR_MISMATCH : QueryPresentationWindowFailure

    data object REVERSED_WINDOW : QueryPresentationWindowFailure

    data object OUTSIDE_RETAINED_RESULT : QueryPresentationWindowFailure

    data object INVALID_ITEM_COUNT : QueryPresentationWindowFailure

    data object MISSING_RETAINED_WINDOW : QueryPresentationWindowFailure

    data object WINDOW_WITHOUT_RETAINED_RESULT : QueryPresentationWindowFailure

    data object RESULT_REFERENCE_MISMATCH : QueryPresentationWindowFailure

    data object ITEM_WINDOW_MISMATCH : QueryPresentationWindowFailure

    data class Accounting(val cause: ImpactAccountingFailure) : QueryPresentationWindowFailure
}

/** Admitted offsets within one still-retained result, carried through every detached output slice. */
@ConsistentCopyVisibility
data class QueryRetainedPresentationWindow
private constructor(
    val reference: QueryResultReference,
    val start: QueryResultCursor,
    val end: QueryResultCursor,
    val resultEnd: QueryResultCursor,
) {
    val itemCount: Int
        get() = end.value - start.value

    val nextCursor: QueryResultCursor?
        get() = if (end == resultEnd) null else end

    internal fun prefix(count: Int): Refinement<QueryRetainedPresentationWindow, QueryPresentationWindowFailure> =
        if (count !in 0..itemCount) Refinement.Rejected(QueryPresentationWindowFailure.INVALID_ITEM_COUNT)
        else Refinement.Refined(QueryRetainedPresentationWindow(reference, start, split(count), resultEnd))

    internal fun suffix(count: Int): Refinement<QueryRetainedPresentationWindow, QueryPresentationWindowFailure> =
        if (count !in 0..itemCount) Refinement.Rejected(QueryPresentationWindowFailure.INVALID_ITEM_COUNT)
        else Refinement.Refined(QueryRetainedPresentationWindow(reference, split(count), end, resultEnd))

    private fun split(count: Int): QueryResultCursor =
        when (val parsed = QueryResultCursor.parse(start.value + count)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("A split inside an admitted result window lost its cursor proof")
        }

    companion object {
        fun create(
            reference: QueryResultReference,
            start: QueryResultCursor,
            end: QueryResultCursor,
            resultEnd: QueryResultCursor,
        ): Refinement<QueryRetainedPresentationWindow, QueryPresentationWindowFailure> =
            when {
                start.value > end.value -> Refinement.Rejected(QueryPresentationWindowFailure.REVERSED_WINDOW)
                end.value > resultEnd.value ->
                    Refinement.Rejected(QueryPresentationWindowFailure.OUTSIDE_RETAINED_RESULT)
                else -> Refinement.Refined(QueryRetainedPresentationWindow(reference, start, end, resultEnd))
            }
    }
}

/** Rows and independently retained evidence are presentation units; only rows advance retained row offsets. */
val QueryRunResult.presentationUnitCount: Int
    get() =
        items.values.size +
            failures.values.size +
            omissions.values.size +
            walkObservations.values.size +
            referenceObservations.values.size +
            discoveryObservations.values.size +
            relationObservations.values.size +
            deferredEvidence.values.size

/** A fitting boundary may expose only the offsets of the rows it actually emits. */
fun QueryRunResult.presentationPrefix(count: Int): Refinement<QueryRunResult, QueryPresentationWindowFailure> =
    presentationSlice(count, suffix = false)

/** The admitted start moves with a suffix; its original final-page status does not erase that start. */
fun QueryRunResult.presentationSuffix(count: Int): Refinement<QueryRunResult, QueryPresentationWindowFailure> =
    presentationSlice(count, suffix = true)

private fun QueryRunResult.presentationSlice(
    count: Int,
    suffix: Boolean,
): Refinement<QueryRunResult, QueryPresentationWindowFailure> {
    if (count !in 0..presentationUnitCount)
        return Refinement.Rejected(QueryPresentationWindowFailure.INVALID_ITEM_COUNT)
    val window =
        when (val admitted = admittedPresentationWindow()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val selectedRowCount = minOf(count, items.values.size)
    val partition = QueryPresentationPartition(count, suffix)
    val bounded = partition.select(items)
    val evidence = selectEvidence(count, suffix)
    val selected = bounded.values
    val selectedWindow =
        when (val selected = if (suffix) window?.suffix(selectedRowCount) else window?.prefix(selectedRowCount)) {
            null -> null
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val accounting =
        when (val selectedAccounting = selectedImpactAccounting(selected, if (suffix) selectedRowCount else 0)) {
            is Refinement.Refined -> selectedAccounting.value
            is Refinement.Rejected ->
                return Refinement.Rejected(QueryPresentationWindowFailure.Accounting(selectedAccounting.failure))
        }
    return Refinement.Refined(
        copy(
            items = bounded,
            failures = evidence.failures,
            omissions = evidence.omissions,
            walkObservations = evidence.walkObservations,
            referenceObservations = evidence.referenceObservations,
            discoveryObservations = evidence.discoveryObservations,
            relationObservations = evidence.relationObservations,
            deferredEvidence = evidence.deferredEvidence,
            evidenceWindow = evidence.evidenceWindow,
            impactAccounting = accounting,
            presentationWindow = selectedWindow,
            nextCursor = selectedWindow?.nextCursor,
        )
    )
}

private data class QueryEvidenceSlice(
    val failures: BoundedProtocolList<QueryItemFailureDocument>,
    val omissions: BoundedProtocolList<QueryRelationOmissionDocument>,
    val walkObservations: BoundedProtocolList<QueryWalkObservationDocument>,
    val referenceObservations: BoundedProtocolList<RelationReferenceOccurrenceDocument>,
    val discoveryObservations: BoundedProtocolList<QueryDiscoveryObservationDocument>,
    val relationObservations: BoundedProtocolList<QueryRelationObservationDocument>,
    val deferredEvidence: BoundedProtocolList<QueryDeferredDetailDocument>,
    val evidenceWindow: QueryEvidenceWindowDocument?,
)

private fun QueryRunResult.selectEvidence(count: Int, suffix: Boolean): QueryEvidenceSlice {
    val evidenceStart = evidenceWindow?.start?.value ?: 0
    val evidenceCutoff = (count - items.values.size).coerceIn(0, presentationUnitCount - items.values.size)
    val evidencePartition =
        QueryEvidencePresentationPartition(
            evidenceStart,
            evidenceCutoff,
            suffix,
            deferredEvidence.values.map { it.selector.ordinal.value }.toSet(),
        )
    val selectedFailures = evidencePartition.select(failures)
    val selectedOmissions = evidencePartition.select(omissions)
    val selectedWalks = evidencePartition.select(walkObservations)
    val selectedReferences = evidencePartition.select(referenceObservations)
    val selectedDiscoveries = evidencePartition.select(discoveryObservations)
    val selectedRelations = evidencePartition.select(relationObservations)
    val selectedDeferred =
        BoundedProtocolList.create(
                deferredEvidence.values.filter {
                    if (suffix) it.selector.ordinal.value >= evidenceStart + evidenceCutoff
                    else it.selector.ordinal.value < evidenceStart + evidenceCutoff
                }
            )
            .presentationSubset()
    val selectedEvidenceWindow = evidenceWindow?.let {
        val split = QueryEvidenceCursor.parse(it.start.value + evidenceCutoff).presentationSubset()
        QueryEvidenceWindowDocument.create(
                if (suffix) split else it.start,
                if (suffix) it.end else split,
                it.total,
                selectedDeferred.values.size,
            )
            .presentationSubset()
    }
    return QueryEvidenceSlice(
        failures = selectedFailures,
        omissions = selectedOmissions,
        walkObservations = selectedWalks,
        referenceObservations = selectedReferences,
        discoveryObservations = selectedDiscoveries,
        relationObservations = selectedRelations,
        deferredEvidence = selectedDeferred,
        evidenceWindow = selectedEvidenceWindow,
    )
}

/** Deferred entries occupy presentation positions without advancing delivered-evidence counts. */
private class QueryEvidencePresentationPartition(
    start: Int,
    private val count: Int,
    private val suffix: Boolean,
    private val deferred: Set<Int>,
) {
    private val start = start
    private var ordinal = start

    fun <T> select(values: BoundedProtocolList<T>): BoundedProtocolList<T> =
        BoundedProtocolList.create(
                values.values.filter {
                    while (ordinal in deferred) ordinal++
                    val selected = if (suffix) ordinal >= start + count else ordinal < start + count
                    ordinal++
                    selected
                }
            )
            .presentationSubset()
}

private fun <Value, Failure> Refinement<Value, Failure>.presentationSubset(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An admitted presentation subset lost its range: $failure")
    }

/** Independent evidence positions never become retained row cursors. */
private class QueryPresentationPartition(private val count: Int, private val suffix: Boolean) {
    private var offset = 0

    fun <T> select(values: BoundedProtocolList<T>): BoundedProtocolList<T> {
        val selectedCount = (count - offset).coerceIn(0, values.values.size)
        offset += values.values.size
        val selected = if (suffix) values.values.drop(selectedCount) else values.values.take(selectedCount)
        return when (val admitted = BoundedProtocolList.create(selected)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("A subset of an admitted query presentation list lost its collection proof")
        }
    }
}

/** Revalidates the explicit window's retained-result identity and emitted row count without inferring offsets. */
fun QueryRunResult.admittedPresentationWindow():
    Refinement<QueryRetainedPresentationWindow?, QueryPresentationWindowFailure> {
    val window = presentationWindow
    val retained = retention as? QueryResultRetention.Retained
    when {
        retained != null && window == null ->
            return Refinement.Rejected(QueryPresentationWindowFailure.MISSING_RETAINED_WINDOW)
        retained == null && window != null ->
            return Refinement.Rejected(QueryPresentationWindowFailure.WINDOW_WITHOUT_RETAINED_RESULT)
        window != null && window.reference != retained?.reference ->
            return Refinement.Rejected(QueryPresentationWindowFailure.RESULT_REFERENCE_MISMATCH)
        window != null && window.itemCount != items.values.size ->
            return Refinement.Rejected(QueryPresentationWindowFailure.ITEM_WINDOW_MISMATCH)
    }
    if (!admitsDeferredRows(retained, window))
        return Refinement.Rejected(QueryPresentationWindowFailure.DETAIL_SELECTOR_MISMATCH)
    if (!admitsDeferredEvidence(retained))
        return Refinement.Rejected(QueryPresentationWindowFailure.DETAIL_SELECTOR_MISMATCH)
    return Refinement.Refined(window)
}

private fun QueryRunResult.admitsDeferredRows(
    retained: QueryResultRetention.Retained?,
    window: QueryRetainedPresentationWindow?,
): Boolean {
    val deferredRows = items.values.withIndex().filter { it.value is QueryResultItemDocument.DeferredDetail }
    if (deferredRows.isEmpty()) return true
    if (retained == null || window == null) return false
    return deferredRows.all { (index, item) ->
        val selector = (item as QueryResultItemDocument.DeferredDetail).detail.selector
        selector.isInitialDetail(retained.reference, QueryEntryDetailTarget.ROW) &&
            selector.ordinal.value == window.start.value + index
    }
}

private fun QueryRunResult.admitsDeferredEvidence(retained: QueryResultRetention.Retained?): Boolean {
    val summaries = deferredEvidence.values
    if (summaries.isEmpty()) return true
    val evidence = evidenceWindow
    if (retained == null || evidence == null) return false
    if (evidence.deferredCount != summaries.size) return false
    if (summaries.map { it.selector.ordinal }.distinct().size != summaries.size) return false
    return summaries.all { summary ->
        val selector = summary.selector
        selector.isInitialDetail(retained.reference, QueryEntryDetailTarget.EVIDENCE) &&
            selector.ordinal.value in evidence.start.value until evidence.end.value
    }
}

private fun QueryEntryDetailSelectorDocument.isInitialDetail(
    reference: QueryResultReference,
    expectedTarget: QueryEntryDetailTarget,
): Boolean {
    if (result != reference || target != expectedTarget) return false
    if (offset.value != 0) return false
    return sha256.value.matches(Regex("[0-9a-f]{64}"))
}
