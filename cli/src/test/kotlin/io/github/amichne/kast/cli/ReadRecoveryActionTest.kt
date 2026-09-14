package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalQueryCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadRecoveryActionTest {
    @Test
    fun `stale or untrusted read authority requires explicit reacquisition`() {
        val cases = listOf(
            Case(CanonicalOperation.SOURCE_READ, CanonicalSourceReadCliDocuments.project(
                OperationOutcome.Rejected(SourceReadRejection.STALE_GENERATION))),
            Case(CanonicalOperation.RELATION_READ, CanonicalReadCliDocuments.projectRelation(
                OperationOutcome.Rejected(RelationReadRejection.SELECTOR_STALE))),
            Case(CanonicalOperation.TRAVERSAL_RUN, CanonicalReadCliDocuments.projectTraversal(
                OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE))),
        ) + QueryReferenceRejectionReason.entries.map { reason ->
            Case(CanonicalOperation.QUERY_RUN, CanonicalQueryCliDocuments.project(
                OperationOutcome.Rejected(QueryRunRejection.ReferenceRejected(
                    (ProtocolOffset.parse(0) as Refinement.Refined).value, reason))))
        }
        with(LiveReadOutputSchemaTest()) {
            for (case in cases) {
                val document = case.projected.document()
                assertEquals("reacquire_authority", document["next_action"]?.jsonPrimitive?.content)
                assertAdmits(case.operation, document)
                assertRejects(case.operation, document.with("next_action", JsonPrimitive("silently_refresh")))
            }
        }
    }

    private data class Case(val operation: CanonicalOperation, val projected: ProjectedCliOutcome)
}
