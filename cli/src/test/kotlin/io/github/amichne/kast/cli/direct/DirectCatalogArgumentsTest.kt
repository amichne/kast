package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.query.PublicToolParameter
import io.github.amichne.kast.appserver.query.PublicToolRule
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.rpc.KastToolRpcBridge
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectCatalogArgumentsTest {
    @Test
    fun `default is common and complete needs one startup selector`() {
        assertEquals(
            Refinement.Refined(DirectCatalogArguments(PublicToolCatalog.COMMON, listOf("catalog"))),
            selectDirectCatalog(listOf("catalog")),
        )
        assertEquals(
            Refinement.Refined(DirectCatalogArguments(PublicToolCatalog.COMPLETE, listOf("query-session"))),
            selectDirectCatalog(listOf("--catalog=COMPLETE", "query-session")),
        )
        for (args in
            listOf(
                listOf("--catalog=UNKNOWN"),
                listOf("--catalog=COMMON", "--catalog=COMPLETE"),
                listOf("catalog", "--catalog=COMPLETE"),
            )) assertTrue(selectDirectCatalog(args) is Refinement.Rejected)
    }

    @Test
    fun `session rejects a value admitted under another catalog before invocation`() {
        val calls = AtomicInteger()
        val identity = PublicToolIdentity.QUERY_SYMBOLS
        val tool =
            DirectToolDocument(
                identity.toolName,
                "Query",
                PublicToolContract.parameters(identity, PublicToolCatalog.COMMON),
                McpStructuredResults.schemaFor(identity.toolName),
                OperationEffect.INTELLIJ_READ,
            )
        val session =
            KastDirectToolSession(
                catalog = listOf(tool),
                root = { error("No root effect") },
                start = { error("No preparation effect") },
                invokePublic = {
                    calls.incrementAndGet()
                    error("No semantic effect")
                },
                queryCatalog = PublicToolCatalog.COMMON,
            )
        val input = PublicToolContract.examples(identity).examples.getValue("scopedInterfaceDeclaration").value
        val admitted =
            (PublicToolContract.admit(identity, input, PublicToolCatalog.COMPLETE) as Refinement.Refined).value
        val exit = session.invokeAdmitted(admitted) as CliExit.PublicInputRejected
        assertEquals(PublicToolInputFailure.SchemaMismatch, exit.failure)
        assertEquals(
            ToolRpcReply.Rejected(ToolRpcFailure.INVALID_ARGUMENTS),
            KastToolRpcBridge(session).callAdmitted(admitted),
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun `minimum rejection preserves the supported allowance and performs no native call`() {
        val identity = PublicToolIdentity.QUERY_SYMBOLS
        val session =
            KastDirectToolSession(
                catalog = emptyList(),
                root = { error("No root effect") },
                start = { error("No preparation effect") },
                invokePublic = { error("No semantic effect") },
                queryCatalog = PublicToolCatalog.COMMON,
            )
        val arguments = Json {
            encodeDefaults = true
        }
            .encodeToJsonElement(MinimumEnvelope.serializer(), MinimumEnvelope())
            .jsonObject
        val exit = session.invoke(identity.toolName, arguments) as CliExit.PublicInputRejected
        assertEquals(
            PublicToolInputFailure.Parameter(
                PublicToolParameter.EXECUTION_BUDGET_MAX_RETURNED_BYTES,
                PublicToolRule.OUTPUT_CONTROL_ALLOWANCE,
            ),
            exit.failure,
        )
        val document = Json.parseToJsonElement(exit.document.value).jsonObject
        assertEquals(setOf("status", "boundary", "reason", "detail"), document.keys)
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("usage", document.getValue("boundary").jsonPrimitive.content)
        assertEquals("invalid-public-tool-arguments", document.getValue("reason").jsonPrimitive.content)
        val detail = document.getValue("detail").jsonObject
        assertEquals(setOf("type", "minimumBytes"), detail.keys)
        assertEquals("OUTPUT_ALLOWANCE_REJECTED", detail.getValue("type").jsonPrimitive.content)
        assertEquals(4096L, detail.getValue("minimumBytes").jsonPrimitive.long)
        assertTrue(exit.document.value.toByteArray(Charsets.UTF_8).size <= 4096)
    }
}

@Serializable private data class MinimumEnvelope(val request: MinimumRun = MinimumRun())

@Serializable
private data class MinimumRun(
    val type: String = "RUN",
    val source: MinimumSource = MinimumSource(),
    val terminalStep: MinimumTerminal = MinimumTerminal(),
    val executionBudget: MinimumBudget = MinimumBudget(),
)

@Serializable
private data class MinimumSource(val type: String = "AT_LOCATION", val file: String = "Fixture.kt", val offset: Int = 0)

@Serializable private data class MinimumTerminal(val type: String = "EMIT")

@Serializable private data class MinimumBudget(val maxReturnedBytes: Long = 4095)
