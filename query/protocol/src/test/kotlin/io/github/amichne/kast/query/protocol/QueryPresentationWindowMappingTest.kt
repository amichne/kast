package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryPresentationWindowFailure
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetainedPresentationWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class QueryPresentationWindowMappingTest {
    private val reference = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()

    @Test
    fun `retained projection cannot manufacture missing bounds or erase unowned bounds`() {
        assertEquals(
            Refinement.Rejected(QueryPresentationWindowFailure.MISSING_RETAINED_WINDOW),
            QueryResultRetention.Retained(reference).presentationWindow(null, 0),
        )
        val window = QueryRetainedPresentationWindow.create(reference, cursor(2), cursor(4), cursor(8)).refined()
        assertEquals(
            Refinement.Rejected(QueryPresentationWindowFailure.WINDOW_WITHOUT_RETAINED_RESULT),
            QueryResultRetention.NotRequested.presentationWindow(window, 2),
        )
        assertEquals(
            Refinement.Rejected(QueryPresentationWindowFailure.ITEM_WINDOW_MISMATCH),
            QueryResultRetention.Retained(reference).presentationWindow(window, 1),
        )
        assertSame(window, QueryResultRetention.Retained(reference).presentationWindow(window, 2).refined())
        assertEquals(cursor(4), window.nextCursor)
    }

    private fun cursor(value: Int) = QueryResultCursor.parse(value).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
