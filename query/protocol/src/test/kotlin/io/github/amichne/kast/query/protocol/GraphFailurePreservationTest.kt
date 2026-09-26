package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRelationFailureDocument
import io.github.amichne.kast.protocol.contract.QueryWalkFailureDocument
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadRejection
import io.github.amichne.kast.traversal.contract.TraversalRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GraphFailurePreservationTest {
    @Test
    fun `actionable one hop causes survive query walk projection independently`() {
        val fixture = RelationPagingFixture.live()
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
        val projected = causes.map { reason ->
            QueryItemFailure.Walk(
                    fixture.selector,
                    RelationMeaning.Callers,
                    TraversalRejection.OneHopRejected(reason),
                )
                .projectIssue(fixture.references) as QueryItemFailureDocument.Walk
        }
        assertEquals(
            causes.map { QueryWalkFailureDocument.OneHop(QueryRelationFailureDocument.valueOf(it.name)) },
            projected.map { it.reason },
        )
    }
}
