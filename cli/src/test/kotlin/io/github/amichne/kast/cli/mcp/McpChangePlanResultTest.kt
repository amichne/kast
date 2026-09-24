package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.InstalledHostedToolDocument
import io.github.amichne.kast.cli.InstalledServerExecutionBudgetDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpChangePlanResultTest {
    @TempDir lateinit var temporary: Path

    private fun admittedRoot(): CanonicalRootDiscovery {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        return FilesystemCanonicalRootDiscovery.discover(temporary)
    }

    private fun invokeChangePlan(exit: CliExit): Pair<JsonObject, String> {
        val output = ByteArrayOutputStream()
        val diagnostics = ByteArrayOutputStream()
        val request =
            mcpWire.encodeToString(
                McpRequest(
                    id = JsonPrimitive(1),
                    method = "tools/call",
                    params = mcpWire.encodeToJsonElement(PlanCallParams()),
                )
            )
        KastMcpServer(
                catalog =
                    listOf(
                        InstalledHostedToolDocument(
                            "change.plan",
                            "change_plan",
                            "Plan change",
                            true,
                            "read",
                            "none",
                            InstalledServerExecutionBudgetDocument(1000, 1000),
                            Json.encodeToJsonElement(PlanSchema("object")),
                            Json.encodeToJsonElement(PlanSchema("object")),
                        )
                    ),
                invoke = { _, _ -> exit },
                root = { admittedRoot() },
                diagnostic = PrintStream(diagnostics),
            )
            .run(BufferedInputStream(ByteArrayInputStream(request.toByteArray())), PrintStream(output))
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject.getValue("result").jsonObject
        return result to diagnostics.toString(Charsets.UTF_8)
    }

    @Test
    fun `change plan preserves a hosted planning evidence rejection`() {
        val rejection =
            CanonicalJsonDocument.generated(TestHostedPlanningRejection.serializer())
                .create(TestHostedPlanningRejection())
        val (result, diagnostics) = invokeChangePlan(CliExit.OperationRejected(rejection))
        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals("rejected", structured.getValue("status").jsonPrimitive.content)
        assertEquals(
            "HOSTED_OPERATION_REJECTED",
            structured.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals(
            Json.parseToJsonElement(rejection.value),
            structured.getValue("error").jsonObject.getValue("evidence"),
        )
        assertTrue(!diagnostics.contains("INVALID_RESULT_SCHEMA"))
    }

    @Test
    fun `change plan returns a stored identity and preview through MCP`() {
        val plan = CanonicalJsonDocument.generated(TestChangePlan.serializer()).create(TestChangePlan())
        val (result, _) = invokeChangePlan(CliExit.Complete(plan))
        assertEquals("false", result.getValue("isError").jsonPrimitive.content)
        val structured = result.getValue("structuredContent").jsonObject
        assertEquals("complete", structured.getValue("status").jsonPrimitive.content)
        assertEquals("plan:${"a".repeat(64)}", structured.getValue("planIdentity").jsonPrimitive.content)
        assertEquals(1, structured.getValue("changes").jsonArray.size)
    }

    @Test
    fun `malformed hosted rejection is not promoted into a change plan result`() {
        val malformed =
            CanonicalJsonDocument.generated(TestMalformedHostedRejection.serializer())
                .create(TestMalformedHostedRejection())
        val (result, diagnostics) = invokeChangePlan(CliExit.OperationRejected(malformed))
        assertEquals(
            "INVALID_RESULT_SCHEMA",
            result
                .getValue("structuredContent")
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("code")
                .jsonPrimitive
                .content,
        )
        val event = diagnostics.lineSequence().filter(String::isNotBlank).last()
        assertTrue(event.contains("\"resultVariant\":\"HOST_REJECTED\""))
        assertTrue(event.contains("\"schemaFailure\":\"MISSING_STATUS\""))
        assertTrue(event.contains("\"schemaField\":\"STATUS\""))
    }
}

@Serializable private data class PlanSchema(val type: String)

@Serializable
private data class PlanCallParams(
    val name: String = "change_plan",
    val arguments: JsonObject = emptyMcpArguments,
    @kotlinx.serialization.SerialName("_meta")
    val meta: McpRequestMeta = McpRequestMeta(MODERN_PROTOCOL_VERSION, emptyMcpArguments),
)

@Serializable
private data class TestChangePlan(
    val operation: String = "change.plan",
    val status: String = "complete",
    val planIdentity: String = "plan:${"a".repeat(64)}",
    val changes: List<TestChangePreview> = listOf(TestChangePreview()),
)

@Serializable
private data class TestChangePreview(
    val path: String = "src/Target.kt",
    val kind: String = "update",
    val diff: String = "+fun added() = Unit",
)

@Serializable
private data class TestHostedPlanningRejection(
    val type: String = "HOST_REJECTED",
    val failure: String = "CHANGE_PLANNING_EVIDENCE_REJECTED",
    val detail: TestPlanningEvidence = TestPlanningEvidence(),
)

@Serializable
private data class TestPlanningEvidence(
    val type: String = "PLANNING_EVIDENCE",
    val cause: TestRelationIncomplete = TestRelationIncomplete(),
)

@Serializable
private data class TestRelationIncomplete(
    val type: String = "RELATION_INCOMPLETE",
    val limitations: List<String> = listOf("UNSUPPORTED_ITEM"),
    val continuation: String = "TERMINAL_INCOMPLETE",
)

@Serializable
private data class TestMalformedHostedRejection(
    val type: String = "HOST_REJECTED",
    val failure: String = "CHANGE_PLANNING_EVIDENCE_REJECTED",
)
