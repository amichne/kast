package io.github.amichne.kast.topology.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TopologyExtractionFailureTest {
    @Test
    fun `semantic extraction failures retain their exact stage`() {
        assertEquals(
            setOf(
                "PROJECT_UNAVAILABLE",
                "FILE_UNAVAILABLE",
                "DOCUMENT_DIRTY",
                "PSI_DOCUMENT_UNCOMMITTED",
                "VFS_CONTENT_MISMATCH",
                "SOURCE_CONTENT_CHANGED_DURING_BUILD",
                "NOT_KOTLIN_PSI",
                "COMPILER_UNAVAILABLE",
                "DECLARATION_EVIDENCE_REJECTED",
                "PROJECTION_REGISTRY_REJECTED",
                "COMPILER_IDENTITY_MISMATCH",
                "REFERENCE_TARGET_REJECTED",
                "OCCURRENCE_REJECTED",
                "EDGE_REJECTED",
                "OVERRIDE_REJECTED",
                "FILE_ADMISSION_REJECTED",
            ),
            TopologyExtractionFailure.entries.mapTo(linkedSetOf(), Enum<*>::name),
        )
    }
}
