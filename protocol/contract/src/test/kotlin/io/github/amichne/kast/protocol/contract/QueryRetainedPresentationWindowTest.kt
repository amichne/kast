package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryRetainedPresentationWindowTest {
    private val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()

    @Test
    fun `retained presentation bounds reject reversed and out of result windows`() {
        assertEquals(
            QueryPresentationWindowFailure.REVERSED_WINDOW,
            (QueryRetainedPresentationWindow.create(reference, cursor(5), cursor(4), cursor(10)) as Refinement.Rejected)
                .failure,
        )
        assertEquals(
            QueryPresentationWindowFailure.OUTSIDE_RETAINED_RESULT,
            (QueryRetainedPresentationWindow.create(reference, cursor(4), cursor(11), cursor(10))
                    as Refinement.Rejected)
                .failure,
        )
    }

    @Test
    fun `slicing rejects a retained payload whose window or result association was lost`() {
        val window = QueryRetainedPresentationWindow.create(reference, cursor(4), cursor(7), cursor(10)).refined()
        val result =
            QueryRunResult(
                bounded(emptyList()),
                bounded(emptyList()),
                retention = QueryResultRetention.Retained(reference),
            )
        assertEquals(
            QueryPresentationWindowFailure.MISSING_RETAINED_WINDOW,
            (result.presentationPrefix(0) as Refinement.Rejected).failure,
        )
        assertEquals(
            QueryPresentationWindowFailure.ITEM_WINDOW_MISMATCH,
            (result.copy(presentationWindow = window).presentationPrefix(0) as Refinement.Rejected).failure,
        )
        val other = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000002").refined()
        assertEquals(
            QueryPresentationWindowFailure.RESULT_REFERENCE_MISMATCH,
            (result
                    .copy(retention = QueryResultRetention.Retained(other), presentationWindow = window)
                    .presentationSuffix(0) as Refinement.Rejected)
                .failure,
        )
    }

    private fun cursor(value: Int) = QueryResultCursor.parse(value).refined()

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
