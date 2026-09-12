package io.github.amichne.kast.topology.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TopologyDocumentReadinessTest {
    @Test
    fun `cached document state distinguishes dirty uncommitted and ready evidence`() {
        assertEquals(
            TopologyDocumentReadiness.DOCUMENT_DIRTY,
            TopologyDocumentReadiness.observe(fileModified = true, psiCommitted = false),
        )
        assertEquals(
            TopologyDocumentReadiness.PSI_DOCUMENT_UNCOMMITTED,
            TopologyDocumentReadiness.observe(fileModified = false, psiCommitted = false),
        )
        assertEquals(
            TopologyDocumentReadiness.READY,
            TopologyDocumentReadiness.observe(fileModified = false, psiCommitted = true),
        )
    }
}
