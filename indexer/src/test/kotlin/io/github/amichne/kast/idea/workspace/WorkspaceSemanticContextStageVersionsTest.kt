package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.transition.WorkspaceStateIdentity
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorkspaceSemanticContextStageVersionsTest {
    @Test
    fun `semantic context changes invalidate semantic facts without invalidating source facts`() {
        val first = semanticContextStageVersions(WorkspaceStateIdentity("first"))
        val second = semanticContextStageVersions(WorkspaceStateIdentity("second"))

        assertEquals(FileStageVersions.CURRENT.source, first.source)
        assertEquals(first.source, second.source)
        assertNotEquals(first.semanticGraph, second.semanticGraph)
        assertNotEquals(first.relationships, second.relationships)
    }

    @Test
    fun `focused indexing without a workspace candidate retains current stage versions`() {
        assertEquals(FileStageVersions.CURRENT, semanticContextStageVersions(null))
    }
}
