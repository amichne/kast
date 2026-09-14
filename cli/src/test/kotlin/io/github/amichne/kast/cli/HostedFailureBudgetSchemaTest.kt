package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `actual host encoding fixtures satisfy both packaged and installed schemas`() {
        assertEncodingFixture("read")
        assertEncodingFixture("endpoint")
    }

    private fun assertEncodingFixture(kind: String) {
        val resource = checkNotNull(javaClass.getResource("/hosted-$kind-failure-encodings.json"))
        val fixtures = Json.decodeFromString<FailureDocuments>(resource.readText())
        val schemaName = if (kind == "read") "hosted-query" else "hosted-endpoint"
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    checkNotNull(CanonicalOperation::class.java.getResource("/ide-hosted/$schemaName.schema.json"))
                        .readText()
                )
        val documents = fixtures.documents.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            if (kind == "read") readFailures else endpointFailures,
            documents.map { it.getValue("failure").jsonPrimitive.content }.toSet(),
        )
        for (document in documents) {
            assertTrue(schema.validate(document.toString(), InputFormat.JSON).isEmpty(), document.toString())
            val unknown = with(LiveReadOutputSchemaTest()) { document.with("failure", JsonPrimitive("UNKNOWN")) }
            val nullBudget = with(LiveReadOutputSchemaTest()) { document.with("execution_budget", JsonNull) }
            assertTrue(schema.validate(unknown.toString(), InputFormat.JSON).isNotEmpty())
            assertTrue(schema.validate(nullBudget.toString(), InputFormat.JSON).isNotEmpty())
            with(LiveReadOutputSchemaTest()) {
                for (operation in
                    listOf(
                        CanonicalOperation.QUERY_RUN,
                        CanonicalOperation.SOURCE_READ,
                        CanonicalOperation.RELATION_READ,
                        CanonicalOperation.TRAVERSAL_RUN,
                    )) {
                    assertAdmits(operation, document)
                    assertRejects(operation, unknown)
                    assertRejects(operation, nullBudget)
                }
            }
        }
    }

    @Serializable private data class FailureDocuments(val documents: List<String>)

    private val endpointFailures =
        setOf(
            "ADMISSION_CAPACITY_EXCEEDED",
            "ADMISSION_DEADLINE_EXCEEDED",
            "INVALID_REQUEST",
            "REQUEST_TOO_LARGE",
            "REQUEST_INCOMPLETE",
            "IO_UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "WRONG_ROOT",
            "OWNERSHIP_CONFLICT",
            "DIRECTORY_REJECTED",
            "SOCKET_UNAVAILABLE",
            "PLATFORM_UNAVAILABLE",
            "RESPONSE_REJECTED",
            "RESULT_TOO_LARGE",
            "APPROVAL_UNAVAILABLE",
            "APPROVAL_REJECTED",
        )
    private val readFailures =
        setOf(
            "CONFIGURATION_REJECTED",
            "RETIRED",
            "WRONG_ENDPOINT",
            "WRONG_PROJECT",
            "BUSY",
            "STALE_REQUEST",
            "INVALID_SELECTION",
            "DECLARATION_NOT_FOUND",
            "AMBIGUOUS_DECLARATION",
            "DECLARATION_IDENTITY_MISMATCH",
            "PROJECT_UNAVAILABLE",
            "INDEXING",
            "WRONG_THREAD",
            "DIRTY_DOCUMENTS",
            "UNCOMMITTED_DOCUMENTS",
            "CONTENT_MOVED",
            "MODEL_MOVED",
            "UNSUPPORTED_MODEL",
            "UNSUPPORTED_DECLARATION",
            "UNRESOLVED_SUPERTYPE",
            "FILE_UNAVAILABLE",
            "FILE_TOO_LARGE",
            "RESULT_LIMIT_EXCEEDED",
            "OUTSIDE_SCOPE",
            "AMBIGUOUS_SCOPE",
            "READ_PREEMPTED",
            "CANCELLED",
            "BUDGET_EXCEEDED",
            "PLATFORM_FAILURE",
            "PROJECT_ADMISSION_REJECTED",
            "MODEL_CAPTURE_REJECTED",
            "READ_EPOCH_REJECTED",
            "FRESHNESS_REJECTED",
            "LIVE_AUTHORITY_REJECTED",
            "NAMED_SOURCE_SCOPE_REJECTED",
        )

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
