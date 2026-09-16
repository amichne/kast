package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.traversal.contract.TraversalRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GraphFailurePreservationTest {
    @Test
    fun `actionable one hop causes survive traversal projection independently`() {
        val causes =
            listOf(
                RelationReadRejection.SCOPE_REJECTED,
                RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE,
                RelationReadRejection.OUTSIDE_SCOPE,
                RelationReadRejection.AMBIGUOUS_SUBJECT,
                RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE,
                RelationReadRejection.COMPILER_CONTRACT_VIOLATION,
                RelationReadRejection.CONTINUATION_CURSOR_MOVED,
                RelationReadRejection.UNSUPPORTED_SUBJECT,
            )
        assertEquals(causes.size, causes.map { TraversalRejection.OneHopRejected(it).protocol() }.distinct().size)
    }
}
