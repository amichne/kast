package io.github.amichne.kast.query.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryRelationObservationSizeTest {
    @Test
    fun `projection preserves the existing ordinary byte estimate`() {
        assertEquals(4396L, projectedRelationObservationBytes(100L))
    }

    @Test
    fun `projection saturation cannot turn oversized evidence into a negative byte cost`() {
        assertEquals(Long.MAX_VALUE, projectedRelationObservationBytes(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, projectedRelationObservationBytes(Long.MAX_VALUE / 3 + 1))
        assertEquals(Long.MAX_VALUE, projectedRelationObservationBytes((Long.MAX_VALUE - 4096) / 3 + 1))
    }
}
