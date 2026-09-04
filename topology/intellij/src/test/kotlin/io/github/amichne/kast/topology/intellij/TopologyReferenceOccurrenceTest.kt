package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class TopologyReferenceOccurrenceTest {
    @Test
    fun `zero width synthetic reference uses its enclosing super type call`() {
        val occurrence = assertInstanceOf(
            TopologyReferenceOccurrence.Admitted::class.java,
            TopologyReferenceOccurrence.refine(
                TextRange(781, 781),
                EnclosingSuperTypeCallRange.Observed(TextRange(781, 792)),
            ),
        )

        assertEquals(781, occurrence.range.startInclusive)
        assertEquals(792, occurrence.range.endExclusive)
    }

    @Test
    fun `non-empty direct reference remains the authoritative occurrence`() {
        val occurrence = assertInstanceOf(
            TopologyReferenceOccurrence.Admitted::class.java,
            TopologyReferenceOccurrence.refine(
                TextRange(10, 16),
                EnclosingSuperTypeCallRange.Observed(TextRange(10, 24)),
            ),
        )

        assertEquals(10, occurrence.range.startInclusive)
        assertEquals(16, occurrence.range.endExclusive)
    }

    @Test
    fun `zero width reference without a source anchor remains rejected`() {
        assertInstanceOf(
            TopologyReferenceOccurrence.Rejected::class.java,
            TopologyReferenceOccurrence.refine(
                TextRange(781, 781),
                EnclosingSuperTypeCallRange.Unavailable,
            ),
        )
    }
}
