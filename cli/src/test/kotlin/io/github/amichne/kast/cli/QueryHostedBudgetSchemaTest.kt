package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.evidenceBasis
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryHostedBudgetSchemaTest {
    @Test
    fun `complete and qualified query envelopes retain valid execution grant metadata`() = runTest {
        with(LiveReadOutputSchemaTest()) {
            val fixture = RelationPagingFixture.live()
            val grant = hostedSchemaBudgetGrant(ExecutionBudgetDocument(maxResults = ResultLimit.parse(999).refined()))
            val result =
                QueryRunResult(
                    BoundedProtocolList.create(emptyList<QueryResultItemDocument>()).refined(),
                    BoundedProtocolList.create(emptyList<QueryItemFailureDocument>()).refined(),
                    executionBudget = ExecutionBudgetReport.from(grant),
                )
            val evidence = EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, fixture.authority.evidenceBasis(), result)
            val qualified =
                OperationOutcome.Qualified(
                    evidence,
                    QueryRunQualification.create(
                            QueryKnownMinimum.parse(0).refined(),
                            listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                            io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument.TerminalIncomplete(
                                io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE
                            ),
                        )
                        .refined(),
                )
            for (outcome in listOf(OperationOutcome.Complete(evidence), qualified)) {
                val document = CanonicalQueryCliDocuments.project(outcome).document()
                assertAdmits(CanonicalOperation.QUERY_RUN, document)
                val report = document.getValue("execution_budget").jsonObject
                val results = report.getValue("max_results").jsonObject
                assertEquals(JsonPrimitive(128), results["effective"])
                assertEquals(JsonPrimitive("caller"), results["selection"])
                assertRejects(
                    CanonicalOperation.QUERY_RUN,
                    document.with(
                        "execution_budget",
                        report.with("max_results", results.with("effective", JsonPrimitive(0))),
                    ),
                )
            }
        }
    }
}
