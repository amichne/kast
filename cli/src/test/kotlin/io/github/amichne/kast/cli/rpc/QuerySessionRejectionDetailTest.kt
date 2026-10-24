package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QuerySessionRejectionDetailTest {
    private val result = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001"))
    private val otherResult = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000003"))
    private val output =
        parsed(QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000002"))
    private val otherOutput =
        parsed(QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000004"))

    @Test
    fun `closed details encode their fixed actions and required fields`() {
        val cases =
            listOf(
                Triple(
                    QuerySessionRejectionDetail.NewRun.value,
                    "NEW_RUN_REQUIRES_NEW_SESSION",
                    setOf("type", "next_action"),
                ),
                Triple(
                    QuerySessionRejectionDetail.SourceRead.value,
                    "SOURCE_READ_REQUIRES_NEW_SESSION",
                    setOf("type", "next_action"),
                ),
                Triple(
                    QuerySessionRejectionDetail.ResultNotIssued(result, otherResult),
                    "RESULT_NOT_ISSUED_BY_PRECEDING_REPLY",
                    setOf("type", "next_action", "expected_result", "requested_result"),
                ),
                Triple(
                    QuerySessionRejectionDetail.ContinuationNotIssued(output, otherOutput),
                    "CONTINUATION_NOT_ISSUED_BY_PRECEDING_REPLY",
                    setOf("type", "next_action", "expected_continuation", "requested_continuation"),
                ),
            )
        for ((detail, tag, keys) in cases) {
            val reply = ToolRpcReply.Rejected.querySession(detail)
            val encoded = Json.encodeToJsonElement<ToolRpcReply>(reply).jsonObject
            assertEquals(setOf("type", "failure", "detail"), encoded.keys)
            assertEquals("rejected", encoded.getValue("type").jsonPrimitive.content)
            assertEquals("QUERY_SESSION_TRANSITION_REJECTED", encoded.getValue("failure").jsonPrimitive.content)
            val actual = encoded.getValue("detail").jsonObject
            assertEquals(keys, actual.keys)
            assertEquals(tag, actual.getValue("type").jsonPrimitive.content)
            assertEquals("START_NEW_QUERY_SESSION", actual.getValue("next_action").jsonPrimitive.content)
            assertEquals(reply, Json.decodeFromString<ToolRpcReply>(encoded.toString()))
        }
    }

    @Test
    fun `unavailable expected authority stays explicit null`() {
        val resultDetail =
            Json.encodeToJsonElement<QuerySessionRejectionDetail>(
                    QuerySessionRejectionDetail.ResultNotIssued(null, result)
                )
                .jsonObject
        assertEquals(JsonNull, resultDetail.getValue("expected_result"))
        assertEquals(result.value, resultDetail.getValue("requested_result").jsonPrimitive.content)
        val outputDetail =
            Json.encodeToJsonElement<QuerySessionRejectionDetail>(
                    QuerySessionRejectionDetail.ContinuationNotIssued(null, output)
                )
                .jsonObject
        assertEquals(JsonNull, outputDetail.getValue("expected_continuation"))
        assertEquals(output.value, outputDetail.getValue("requested_continuation").jsonPrimitive.content)
    }

    @Test
    fun `source anchor failure binds its cause to invalid arguments`() {
        for (cause in SourceReadAnchorDocumentFailure.entries) {
            val reply = ToolRpcReply.Rejected.querySession(QuerySessionRejectionDetail.SourceAnchor(cause))
            val encoded = Json.encodeToJsonElement<ToolRpcReply>(reply).jsonObject
            assertEquals("INVALID_ARGUMENTS", encoded.getValue("failure").jsonPrimitive.content)
            val actual = encoded.getValue("detail").jsonObject
            assertEquals(setOf("type", "cause", "next_action"), actual.keys)
            assertEquals("SOURCE_ANCHOR_REJECTED", actual.getValue("type").jsonPrimitive.content)
            assertEquals(cause.name, actual.getValue("cause").jsonPrimitive.content)
            assertEquals("USE_ISSUED_CANDIDATE_SELECTOR", actual.getValue("next_action").jsonPrimitive.content)
        }
    }

    @Test
    fun `recovery action from another detail cannot decode`() {
        // Deliberately contradictory external detail proves rejection, not a valid fixture.
        val malformed = Json.encodeToString(ContradictorySessionDetail())
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<QuerySessionRejectionDetail>(malformed)
        }
    }

    @Test
    fun `constraint details reject unknown parameter paths rules and remedies`() {
        // Deliberately invalid external values; valid encoded shapes are proved through both RPC ingress owners.
        val cases =
            listOf(
                InvalidConstraintDetail(parameters = listOf("EXECUTION_MODE", "executionBudget.maxWorkUnits")),
                InvalidConstraintDetail(rule = "UNKNOWN"),
                InvalidConstraintDetail(remedies = listOf("START_NEW_QUERY_SESSION")),
            )
        for (detail in cases) {
            val malformed = Json.encodeToString(detail)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString<QuerySessionRejectionDetail>(malformed)
            }
        }
    }

    @Test
    fun `legacy rejection stays exactly two fields`() {
        val reply = ToolRpcReply.Rejected(ToolRpcFailure.OUT_OF_SCOPE)
        val encoded = Json.encodeToJsonElement<ToolRpcReply>(reply).jsonObject
        assertEquals(setOf("type", "failure"), encoded.keys)
        assertEquals(reply, Json.decodeFromString<ToolRpcReply>(Json.encodeToString<ToolRpcReply>(reply)))
    }

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit test value: ${refinement.failure}")
        }
}

@Serializable
private data class ContradictorySessionDetail(
    @Required val type: String = "SOURCE_READ_REQUIRES_NEW_SESSION",
    @Required
    @SerialName("next_action")
    val nextAction: SourceAnchorRecoveryAction = SourceAnchorRecoveryAction.USE_ISSUED_CANDIDATE_SELECTOR,
)

@Serializable
private data class InvalidConstraintDetail(
    @Required val type: String = "REQUEST_CONSTRAINT_REJECTED",
    @Required val parameters: List<String> = listOf("executionMode", "executionBudget.maxWorkUnits"),
    @Required val rule: String = "RUNNER_CONTROLLED_WORK_LIMIT",
    @Required val remedies: List<String> = listOf("REMOVE_MAX_WORK_UNITS", "SELECT_BOUNDED"),
)
