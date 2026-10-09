package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryPresentationWindowFailure {
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
            relationObservations.values.size

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
    val selectedFailures = partition.select(failures)
    val selectedOmissions = partition.select(omissions)
    val selectedWalks = partition.select(walkObservations)
    val selectedReferences = partition.select(referenceObservations)
    val selectedDiscoveries = partition.select(discoveryObservations)
    val selectedRelations = partition.select(relationObservations)
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
            failures = selectedFailures,
            omissions = selectedOmissions,
            walkObservations = selectedWalks,
            referenceObservations = selectedReferences,
            discoveryObservations = selectedDiscoveries,
            relationObservations = selectedRelations,
            impactAccounting = accounting,
            presentationWindow = selectedWindow,
            nextCursor = selectedWindow?.nextCursor,
        )
    )
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

private fun QueryRunResult.admittedPresentationWindow():
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
    return Refinement.Refined(window)
}
