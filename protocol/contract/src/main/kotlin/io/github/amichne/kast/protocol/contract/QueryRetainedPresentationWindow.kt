package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

enum class QueryPresentationWindowFailure {
    REVERSED_WINDOW,
    OUTSIDE_RETAINED_RESULT,
    INVALID_ITEM_COUNT,
    MISSING_RETAINED_WINDOW,
    WINDOW_WITHOUT_RETAINED_RESULT,
    RESULT_REFERENCE_MISMATCH,
    ITEM_WINDOW_MISMATCH,
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
    if (count !in 0..items.values.size) return Refinement.Rejected(QueryPresentationWindowFailure.INVALID_ITEM_COUNT)
    val window =
        when (val admitted = admittedPresentationWindow()) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    val selected = if (suffix) items.values.drop(count) else items.values.take(count)
    val bounded =
        when (val admitted = BoundedProtocolList.create(selected)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("A subset of an admitted query item list lost its collection proof")
        }
    val selectedWindow =
        when (val selected = if (suffix) window?.suffix(count) else window?.prefix(count)) {
            null -> null
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    return Refinement.Refined(
        copy(items = bounded, presentationWindow = selectedWindow, nextCursor = selectedWindow?.nextCursor)
    )
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
