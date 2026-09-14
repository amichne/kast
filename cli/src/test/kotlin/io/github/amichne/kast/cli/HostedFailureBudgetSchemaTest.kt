package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedFailureBudgetSchemaTest {
    @Test
    fun `all four read tool schemas preserve hosted failure reports and reject invalid metadata`() {
        val report = ExecutionBudgetReport.from(hostedSchemaBudgetGrant(ExecutionBudgetDocument()))
        val json = Json { encodeDefaults = true }
        val documents =
            listOf(
                json.encodeToJsonElement(EndpointFailure.serializer(), EndpointFailure(report)).jsonObject,
                json.encodeToJsonElement(ReadFailure.serializer(), ReadFailure(report)).jsonObject,
            )
        with(LiveReadOutputSchemaTest()) {
            for (operation in
                listOf(
                    CanonicalOperation.QUERY_RUN,
                    CanonicalOperation.SOURCE_READ,
                    CanonicalOperation.RELATION_READ,
                    CanonicalOperation.TRAVERSAL_RUN,
                )) {
                for (document in documents) {
                    assertAdmits(operation, document)
                    assertRejects(operation, document.with("execution_budget", JsonNull))
                    val budget = document.getValue("execution_budget").jsonObject
                    val work = budget.getValue("max_work_units").jsonObject
                    assertRejects(
                        operation,
                        document.with(
                            "execution_budget",
                            budget.with(
                                "max_work_units",
                                work.with("effective", JsonPrimitive(0)),
                            ),
                        ),
                    )
                    assertRejects(operation, document.with("failure", JsonPrimitive("UNKNOWN")))
                }
            }
        }
    }

    @Test
    fun `packaged hosted failures reuse the canonical generated budget report schema`() {
        val expected = generatedRequestSchema(ExecutionBudgetReport.serializer())
        for (name in listOf("hosted-query", "hosted-endpoint")) {
            val schema =
                Json.parseToJsonElement(
                        checkNotNull(CanonicalOperation::class.java.getResource("/ide-hosted/$name.schema.json"))
                            .readText()
                    )
                    .jsonObject
            assertEquals(expected, schema.getValue("\$defs").jsonObject["executionBudget"])
        }
    }

    @Serializable
    private data class EndpointFailure(
        @kotlinx.serialization.SerialName("execution_budget") val executionBudget: ExecutionBudgetReport,
        val failure: String = "RESULT_TOO_LARGE",
        val type: String = "HOST_REJECTED",
    )

    @Serializable
    private data class ReadFailure(
        @kotlinx.serialization.SerialName("execution_budget") val executionBudget: ExecutionBudgetReport,
        val failure: String = "BUDGET_EXCEEDED",
        val detail: String = "BUDGET_EXCEEDED",
        val stage: String = "SEMANTIC_READ",
        val schemaVersion: Int = 1,
        val outcome: String = "rejected",
    )
}
