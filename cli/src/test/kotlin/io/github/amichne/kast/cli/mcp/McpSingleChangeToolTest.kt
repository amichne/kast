package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpSingleChangeToolTest {
    @TempDir lateinit var root: Path

    @Test
    fun `direct MCP advertises one mutation tool instead of its internal phases`() {
        val visible = mcpVisibleDefinitions(CanonicalAgentToolDefinitions.all).map { it.name.value }
        assertFalse(visible.any { it in setOf("change_plan", "change_apply", "change_recover") })
        assertTrue(visible.contains("search_classes"))
    }

    @Test
    fun `one call plans authorizes applies and returns verified receipt`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                    root,
                    Json.encodeToJsonElement(TestInputSchema()),
                    { phase, _ ->
                        phases += phase
                        when (phase) {
                            McpChangePhase.PLAN -> complete(TestPlan())
                            McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                            McpChangePhase.APPLY -> complete(TestApplication())
                            McpChangePhase.PREPARE_RECOVER,
                            McpChangePhase.RECOVER -> error("verified apply must not recover")
                        }
                    },
                    { "signed-for-${it.operation}" },
                )
                .tool

        val result = tool.invoke(Json.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.Complete::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        assertEquals("complete", output.getValue("status").jsonPrimitive.content)
        assertTrue(McpStructuredResults.validates("change", output))
        assertEquals(
            "receipt:verified",
            output.getValue("application").jsonObject.getValue("receiptIdentity").jsonPrimitive.content,
        )
        assertEquals(listOf(McpChangePhase.PLAN, McpChangePhase.PREPARE_APPLY, McpChangePhase.APPLY), phases)
    }

    @Test
    fun `unverified apply attempts recovery in the same call and preserves both outcomes`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                    root,
                    Json.encodeToJsonElement(TestInputSchema()),
                    { phase, _ ->
                        phases += phase
                        when (phase) {
                            McpChangePhase.PLAN -> complete(TestPlan())
                            McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                            McpChangePhase.APPLY -> qualified(TestUnverifiedApplication())
                            McpChangePhase.PREPARE_RECOVER -> complete(challenge("CHANGE_RECOVER"))
                            McpChangePhase.RECOVER -> complete(TestRecovery())
                        }
                    },
                    { "signed-for-${it.operation}" },
                )
                .tool

        val result = tool.invoke(Json.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        assertEquals("rejected", output.getValue("status").jsonPrimitive.content)
        assertTrue(McpStructuredResults.validates("change", output))
        val error = output.getValue("error").jsonObject
        assertEquals("APPLICATION_UNVERIFIED", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            "recovery_required",
            error.getValue("application").jsonObject.getValue("state").jsonPrimitive.content,
        )
        assertEquals("restored", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(McpChangePhase.RECOVER, phases.last())
    }

    @Test
    fun `lost apply response triggers recovery and cannot claim verified success`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                    root,
                    Json.encodeToJsonElement(TestInputSchema()),
                    { phase, _ ->
                        phases += phase
                        when (phase) {
                            McpChangePhase.PLAN -> complete(TestPlan())
                            McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                            McpChangePhase.APPLY -> error("transport lost after attempted write")
                            McpChangePhase.PREPARE_RECOVER -> complete(challenge("CHANGE_RECOVER"))
                            McpChangePhase.RECOVER -> complete(TestRecovery())
                        }
                    },
                    { "signed-for-${it.operation}" },
                )
                .tool

        val result = tool.invoke(Json.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        val error = output.getValue("error").jsonObject
        assertEquals("APPLICATION_RESPONSE_UNAVAILABLE", error.getValue("code").jsonPrimitive.content)
        assertEquals("restored", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(McpChangePhase.RECOVER, phases.last())
    }

    @Test
    fun `planning rejection cannot enter the write path`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                    root,
                    Json.encodeToJsonElement(TestInputSchema()),
                    { phase, _ ->
                        phases += phase
                        CliExit.OperationRejected(document(TestPlanningRejection()))
                    },
                    { error("no challenge expected") },
                )
                .tool

        val result = tool.invoke(Json.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        assertEquals(listOf(McpChangePhase.PLAN), phases)
        assertEquals(
            "PLAN_REJECTED",
            Json.parseToJsonElement(result.document.value)
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("code")
                .jsonPrimitive
                .content,
        )
    }

    private fun challenge(operation: String) =
        ApprovalChallenge(
            version = 1,
            operation = operation,
            root = root.toString(),
            host = "host",
            planId = "a".repeat(64),
            challenge = "b".repeat(64),
            preview = ApprovalPreview("src/Target.kt", "+fun added() = Unit"),
        )

    private inline fun <reified T> document(value: T) = CanonicalJsonDocument.generated(serializer<T>()).create(value)

    private inline fun <reified T> complete(value: T) = CliExit.Complete(document(value))

    private inline fun <reified T> qualified(value: T) = CliExit.Qualified(document(value))
}

@Serializable private data class TestInputSchema(val type: String = "object")

@Serializable private data class TestIntent(val intent: String = "add-declaration")

@Serializable
private data class TestPlan(val status: String = "complete", val planIdentity: String = "plan:${"a".repeat(64)}")

@Serializable
private data class TestApplication(
    val status: String = "complete",
    val state: String = "verified",
    val receiptIdentity: String = "receipt:verified",
)

@Serializable
private data class TestUnverifiedApplication(val status: String = "qualified", val state: String = "recovery_required")

@Serializable private data class TestRecovery(val status: String = "complete", val state: String = "restored")

@Serializable private data class TestPlanningRejection(val status: String = "rejected", val reason: String = "stale")
