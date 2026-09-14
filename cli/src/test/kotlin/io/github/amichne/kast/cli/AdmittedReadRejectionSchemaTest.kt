package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CanonicalQueryCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalReadCliDocuments
import io.github.amichne.kast.cli.projection.CanonicalSourceReadCliDocuments
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.AdmittedRelationReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedSourceReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedTraversalRunRejection
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdmittedReadRejectionSchemaTest {
    @Test
    fun `admitted read rejection projections preserve reasons and require a nonnull valid budget`() {
        with(LiveReadOutputSchemaTest()) {
            val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
            for (case in cases(report)) {
                val document = case.projected.document()
                assertEquals(
                    setOf(
                        "operation",
                        "status",
                        "execution_budget",
                        if (case.operation == CanonicalOperation.QUERY_RUN) "rejection" else "reason",
                    ),
                    document.keys,
                )
                assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
                val reason =
                    if (case.operation == CanonicalOperation.QUERY_RUN)
                        document.getValue("rejection").jsonObject.getValue("type")
                    else document.getValue("reason")
                assertEquals(case.reason, reason.jsonPrimitive.content)
                assertAdmits(case.operation, document)
                assertRejects(case.operation, document.with("execution_budget", JsonNull))
                val budget = document.getValue("execution_budget").jsonObject
                val work = budget.getValue("max_work_units").jsonObject
                assertRejects(
                    case.operation,
                    document.with(
                        "execution_budget",
                        budget.with("max_work_units", work.with("effective", JsonPrimitive(0))),
                    ),
                )
            }
        }
    }

    private fun cases(report: ExecutionBudgetReport): List<Case> =
        listOf(
            Case(
                CanonicalOperation.SOURCE_READ,
                CanonicalSourceReadCliDocuments.project(
                    OperationOutcome.Rejected(
                        AdmittedSourceReadRejection(SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE, report)
                    )
                ),
                "compiler-analysis-unavailable",
            ),
            Case(
                CanonicalOperation.RELATION_READ,
                CanonicalReadCliDocuments.projectRelation(
                    OperationOutcome.Rejected(
                        AdmittedRelationReadRejection(RelationReadRejection.SELECTOR_STALE, report)
                    )
                ),
                "selector-stale",
            ),
            Case(
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalReadCliDocuments.projectTraversal(
                    OperationOutcome.Rejected(
                        AdmittedTraversalRunRejection(TraversalRunRejection.SELECTOR_STALE, report)
                    )
                ),
                "selector-stale",
            ),
            Case(
                CanonicalOperation.QUERY_RUN,
                CanonicalQueryCliDocuments.project(
                    OperationOutcome.Rejected(AdmittedQueryRunRejection(QueryRunRejection.WorkspaceNotReady, report))
                ),
                "workspace-not-ready",
            ),
        )

    private data class Case(val operation: CanonicalOperation, val projected: ProjectedCliOutcome, val reason: String)
}
