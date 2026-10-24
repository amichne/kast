package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.cli.direct.DirectToolRegistration
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directToolName
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import java.io.ByteArrayInputStream
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastToolRpcConstraintAdmissionTest {
    @Test
    fun `direct RUN ingress preserves the work constraint in its encoded rejection`() {
        for (json in encoders()) {
            for (mode in listOf(null, QueryExecutionModeDocument.RUNNER_CONTROLLED)) {
                val input = json.encodeToString(ConstraintAdmissionInput(runWithWork(mode, 1)))
                assertConstraint(json, directReply(input))
            }
        }
    }

    @Test
    fun `query-session ingress preserves the work constraint in its encoded rejection`() {
        for (json in encoders()) {
            for (mode in listOf(null, QueryExecutionModeDocument.RUNNER_CONTROLLED)) {
                val input = json.encodeToString(ConstraintAdmissionInput(runWithWork(mode, 1)))
                assertConstraint(json, sessionReply(input))
            }
        }
    }

    @Test
    fun `ordinary parameter and schema failures keep the coarse compatibility shape`() {
        val invalidName = ConstraintAdmissionRun(ConstraintAdmissionSource.Name("qualified.Service"))
        for (json in encoders()) {
            for (run in listOf(invalidName, runWithWork(QueryExecutionModeDocument.BOUNDED, 0))) {
                val input = json.encodeToString(ConstraintAdmissionInput(run))
                for (reply in listOf(directReply(input), sessionReply(input))) {
                    assertEquals(ToolRpcReply.Rejected(ToolRpcFailure.INVALID_ARGUMENTS), reply)
                    val encoded = json.parseToJsonElement(json.encodeToString<ToolRpcReply>(reply)).jsonObject
                    assertEquals(setOf("type", "failure"), encoded.keys)
                }
            }
        }
    }

    private fun encoders(): List<Json> = listOf(Json, Json { encodeDefaults = true })

    private fun runWithWork(mode: QueryExecutionModeDocument?, work: Long): ConstraintAdmissionRun =
        ConstraintAdmissionRun(
            ConstraintAdmissionSource.All,
            executionMode = mode,
            executionBudget = ConstraintAdmissionBudget(work),
        )

    private fun directReply(input: String): ToolRpcReply {
        val registration =
            DirectToolRegistration(setOf(directToolName("query_symbols"))) {
                error("Rejected input cannot project the catalog")
            }
        val session =
            KastDirectToolSession(
                registration,
                root = { error("Rejected input cannot discover a workspace") },
                start = { error("Rejected input cannot start preparation") },
                invokePublic = { error("Rejected input cannot invoke semantics") },
            )
        return KastToolRpcBridge(session).call("query_symbols", input)
    }

    private fun sessionReply(input: String): ToolRpcReply {
        val replies = mutableListOf<ToolRpcReply>()
        KastToolRpcQuerySession { error("Rejected input cannot invoke semantics") }
            .run(ByteArrayInputStream("$input\n".toByteArray()), replies::add)
        return replies.single()
    }

    private fun assertConstraint(json: Json, reply: ToolRpcReply) {
        val encoded = json.parseToJsonElement(json.encodeToString<ToolRpcReply>(reply)).jsonObject
        assertEquals(setOf("type", "failure", "detail"), encoded.keys)
        assertEquals("rejected", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("INVALID_ARGUMENTS", encoded.getValue("failure").jsonPrimitive.content)
        val detail = encoded.getValue("detail").jsonObject
        assertEquals(setOf("type", "parameters", "rule", "remedies"), detail.keys)
        assertEquals("REQUEST_CONSTRAINT_REJECTED", detail.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("executionMode", "executionBudget.maxWorkUnits"),
            detail.getValue("parameters").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("RUNNER_CONTROLLED_WORK_LIMIT", detail.getValue("rule").jsonPrimitive.content)
        assertEquals(
            listOf("REMOVE_MAX_WORK_UNITS", "SELECT_BOUNDED"),
            detail.getValue("remedies").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(reply, json.decodeFromString<ToolRpcReply>(encoded.toString()))
    }
}

@Serializable private data class ConstraintAdmissionInput(val request: ConstraintAdmissionRun)

@Serializable
private data class ConstraintAdmissionRun(
    val source: ConstraintAdmissionSource,
    @Required val type: String = "RUN",
    @Required val terminalStep: ConstraintAdmissionTerminal = ConstraintAdmissionTerminal(),
    val executionMode: QueryExecutionModeDocument? = null,
    val executionBudget: ConstraintAdmissionBudget? = null,
)

@Serializable
private sealed interface ConstraintAdmissionSource {
    @Serializable @SerialName("ALL_DECLARATIONS") data object All : ConstraintAdmissionSource

    @Serializable
    @SerialName("SEARCH_DECLARATIONS")
    data class Name(val declarationName: String) : ConstraintAdmissionSource
}

@Serializable private data class ConstraintAdmissionTerminal(@Required val type: String = "EMIT")

@Serializable private data class ConstraintAdmissionBudget(val maxWorkUnits: Long)
