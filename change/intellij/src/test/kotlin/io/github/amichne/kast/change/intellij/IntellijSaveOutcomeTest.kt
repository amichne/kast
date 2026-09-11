package io.github.amichne.kast.change.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntellijSaveOutcomeTest {
    @Test
    fun `a silently vetoed save cannot establish saved postimage`() {
        assertEquals(
            IntellijSaveOutcome.DOCUMENT_REMAINS_UNSAVED,
            intellijSaveOutcome(unsaved = true, committed = true, observedText = "after", expectedText = "after"),
        )
    }

    @Test
    fun `save must retain committed exact document postimage`() {
        assertEquals(
            IntellijSaveOutcome.DOCUMENT_REMAINS_UNCOMMITTED,
            intellijSaveOutcome(unsaved = false, committed = false, observedText = "after", expectedText = "after"),
        )
        assertEquals(
            IntellijSaveOutcome.DOCUMENT_IMAGE_CHANGED,
            intellijSaveOutcome(
                unsaved = false,
                committed = true,
                observedText = "racing edit",
                expectedText = "after",
            ),
        )
        assertEquals(
            IntellijSaveOutcome.SAVED_COMMITTED_IMAGE,
            intellijSaveOutcome(unsaved = false, committed = true, observedText = "after", expectedText = "after"),
        )
    }
}
