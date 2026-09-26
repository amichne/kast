package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryResultReferencesTest {
    @Test
    fun `retained result reference cannot be an exact symbol or execution continuation`() {
        assertInstanceOf(
            Refinement.Refined::class.java,
            QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001"),
        )
        for (other in listOf("exact:v5:opaque", "query:v1:00000000-0000-0000-0000-000000000001")) {
            assertEquals(
                Refinement.Rejected(QueryResultReferenceFailure.MALFORMED),
                QueryResultReference.parse(other),
            )
        }
    }

    @Test
    fun `presentation cursor has a bounded nonnegative domain`() {
        assertEquals(Refinement.Refined(QueryResultCursor.Start), QueryResultCursor.parse(0))
        for (outside in listOf(-1, 1_000_001)) {
            assertEquals(Refinement.Rejected(QueryResultCursorFailure.OUT_OF_RANGE), QueryResultCursor.parse(outside))
        }
    }
}
