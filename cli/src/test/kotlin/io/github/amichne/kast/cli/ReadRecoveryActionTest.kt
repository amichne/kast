package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryAdmissionCorrectionDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryElementTypeDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QuerySourceRejectionReason
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.recoveryAction
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadRecoveryActionTest {
    @Test
    fun `stale or untrusted read authority requires explicit reacquisition`() {
        val cases =
            listOf(
                Case(
                    CanonicalOperation.SOURCE_READ,
                    CanonicalSourceReadCliDocuments.project(
                        OperationOutcome.Rejected(SourceReadRejection.STALE_GENERATION)
                    ),
                ),
                Case(
                    CanonicalOperation.RELATION_READ,
                    CanonicalReadCliDocuments.projectRelation(
                        OperationOutcome.Rejected(RelationReadRejection.SELECTOR_STALE)
                    ),
                ),
                Case(
                    CanonicalOperation.TRAVERSAL_RUN,
                    CanonicalReadCliDocuments.projectTraversal(
                        OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE)
                    ),
                ),
            ) +
                QueryReferenceRejectionReason.entries.filter(::requiresReacquisition).map { reason ->
                    Case(
                        CanonicalOperation.QUERY_RUN,
                        CanonicalQueryCliDocuments.project(
                            OperationOutcome.Rejected(
                                QueryRunRejection.ReferenceRejected(
                                    (ProtocolOffset.parse(0) as Refinement.Refined).value,
                                    reason,
                                )
                            )
                        ),
                    )
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

    @Test
    fun `recovery directions retain prerequisites without manufacturing retries`() {
        assertEquals(ReadRecoveryAction.SAVE_SOURCE, SourceReadRejection.DOCUMENT_DIRTY.recoveryAction())
        assertEquals(ReadRecoveryAction.SAVE_SOURCE, SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED.recoveryAction())
        assertEquals(ReadRecoveryAction.WAIT_FOR_WORKSPACE, QueryRunRejection.WorkspaceNotReady.recoveryAction())
        assertEquals(ReadRecoveryAction.RESTART_READ, SourceReadRejection.CONTINUATION_UNAVAILABLE.recoveryAction())
        assertEquals(ReadRecoveryAction.RESTART_READ, RelationReadRejection.CONTINUATION_UNAVAILABLE.recoveryAction())
        assertEquals(ReadRecoveryAction.RESTART_READ, TraversalRunRejection.CONTINUATION_UNAVAILABLE.recoveryAction())
        assertEquals(
            ReadRecoveryAction.CORRECT_REQUEST,
            RelationReadRejection.CONTINUATION_SCOPE_MISMATCH.recoveryAction(),
        )
        assertEquals(ReadRecoveryAction.CORRECT_REQUEST, TraversalRunRejection.PLAN_REJECTED.recoveryAction())
        assertEquals(ReadRecoveryAction.REPORT_FAILURE, SourceReadRejection.CONTRACT_VIOLATION.recoveryAction())
        assertEquals(
            ReadRecoveryAction.REPORT_FAILURE,
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
                .recoveryAction(),
        )
        assertEquals(
            ReadRecoveryAction.REACQUIRE_AUTHORITY,
            RelationReadRejection.CONTINUATION_MALFORMED.recoveryAction(),
        )
        assertEquals(
            ReadRecoveryAction.REACQUIRE_AUTHORITY,
            TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH.recoveryAction(),
        )
    }

    @Test
    fun `every query failure derives the same action after wire decoding with or without admission`() {
        val offset = (ProtocolOffset.parse(0) as Refinement.Refined).value
        val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
        val reasons =
            listOf(
                QueryRunRejection.WorkspaceNotReady,
                QueryRunRejection.PlanRejected(
                    offset,
                    QueryElementTypeDocument.EXACT_SYMBOL,
                    QueryElementTypeDocument.DECLARATION_CANDIDATE,
                    QueryAdmissionCorrectionDocument.INSERT_INSPECT,
                ),
                QueryRunRejection.SourceRejected(
                    QueryDeclarationKindDocument.CONSTRUCTOR,
                    QuerySourceRejectionReason.UNSUPPORTED_DECLARATION_KIND,
                ),
            ) +
                QueryReferenceRejectionReason.entries.map { QueryRunRejection.ReferenceRejected(offset, it) } +
                QueryExecutionRejectionDocument.entries.map { QueryRunRejection.ExecutionRejected(it) }
        val binding = CanonicalOperationWireBindings.queryRun
        with(LiveReadOutputSchemaTest()) {
            for (reason in reasons) {
                for (failure in listOf<QueryRunFailure>(reason, AdmittedQueryRunRejection(reason, report))) {
                    val outcome = OperationOutcome.Rejected(failure)
                    val wire = binding.encodeOutcome(outcome) as WireEncoding.Encoded
                    val decoded = binding.decodeOutcome(wire.document) as WireDecoding.Decoded
                    assertEquals(outcome, decoded.value)
                    val before = CanonicalQueryCliDocuments.project(outcome).document()
                    val after = CanonicalQueryCliDocuments.project(decoded.value).document()
                    assertEquals(before, after)
                    assertEquals(
                        Json.encodeToJsonElement(ReadRecoveryAction.serializer(), failure.recoveryAction()),
                        after.getValue("next_action"),
                    )
                    assertEquals(failure is AdmittedQueryRunRejection, "execution_budget" in after)
                    assertAdmits(CanonicalOperation.QUERY_RUN, after)
                    assertRejects(
                        CanonicalOperation.QUERY_RUN,
                        after.with("next_action", JsonPrimitive("silently_refresh")),
                    )
                    val missing = MissingQueryRecovery(after.getValue("rejection").jsonObject)
                    val json = Json { encodeDefaults = true }
                    assertRejects(
                        CanonicalOperation.QUERY_RUN,
                        json.encodeToJsonElement(MissingQueryRecovery.serializer(), missing).jsonObject,
                    )
                }
            }
        }
    }

    /** Rejection remains opaque here; this deliberately incomplete envelope omits only the required action. */
    @Serializable
    private data class MissingQueryRecovery(
        val rejection: JsonObject,
        val operation: String = "query.run",
        val status: String = "rejected",
    )

    private fun requiresReacquisition(reason: QueryReferenceRejectionReason): Boolean =
        QueryRunRejection.ReferenceRejected((ProtocolOffset.parse(0) as Refinement.Refined).value, reason)
            .recoveryAction() == ReadRecoveryAction.REACQUIRE_AUTHORITY

    private data class Case(val operation: CanonicalOperation, val projected: ProjectedOperationOutcome)
}
