package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A caller list is not a call-site list; the summary counts the calls it set aside inside stored lambdas. */
internal class CallbackExclusionSummaryTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `a complete caller list that set aside a stored-lambda call counts it in every presentation`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(observation(true)),
                    )
                },
            )
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .execute(strict, fixture.authority, budget, policy())
        assertTrue(result is OperationOutcome.Complete, "Expected a complete caller list, received $result")
        for (document in presented(result)) {
            val summary = document.getValue("original_evidence_summary").jsonObject
            assertEquals(1, summary["excluded_callback_calls"]?.jsonPrimitive?.int, "summary: $summary")
        }
        script.assertDrained()
    }

    @Test
    fun `a complete answer with no stored-lambda call encodes no exclusion count`() = runTest {
        val script =
            Script(
                listOf(listOf(row)),
                resultForPage = { rows, _ -> QueryResult(QueryRows.Symbols.of(rows), emptyList()) },
            )
        val result =
            CanonicalQueryProtocol(script.operations, fixture.references)
                .execute(strict, fixture.authority, budget, policy())
        assertTrue(result is OperationOutcome.Complete, "Expected a complete answer, received $result")
        for (document in presented(result)) {
            assertEquals(null, document.getValue("original_evidence_summary").jsonObject["excluded_callback_calls"])
        }
        script.assertDrained()
    }

    private fun presented(result: QueryPublishedPage): List<JsonObject> {
        val projected = CanonicalQueryCliDocuments.project(result) as ProjectedOperationOutcome.Complete
        return ToolOutputDetail.entries.map { Json.parseToJsonElement(projected.document.present(it).value).jsonObject }
    }
}
