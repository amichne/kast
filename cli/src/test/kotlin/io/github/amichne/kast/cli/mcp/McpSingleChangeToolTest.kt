package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpSingleChangeToolTest {
    @TempDir lateinit var root: Path

    @Test
    fun `direct MCP advertises both live mutation tools instead of internal phases`() {
        val visible = CanonicalAgentToolDefinitions.all.map { it.name.value }
        assertFalse(visible.any { it in setOf("change_plan", "change_apply", "change_recover") })
        assertTrue(visible.contains("query_symbols"))
        assertTrue(visible.containsAll(listOf("add_declaration", "replace_body")))
    }

    @Test
    fun `one call plans authorizes applies and returns verified receipt`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
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

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        val admission =
            PublicToolContract.admit(
                PublicToolIdentity.ADD_DECLARATION,
                Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()),
            )
        assertTrue(admission is Refinement.Refined, admission.toString())
        assertInstanceOf(CliExit.Complete::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        assertEquals("complete", output.getValue("status").jsonPrimitive.content)
        assertTrue(McpStructuredResults.validates("add_declaration", output))
        assertEquals(
            "receipt:verified",
            output.getValue("application").jsonObject.getValue("receiptIdentity").jsonPrimitive.content,
        )
        assertEquals(listOf(McpChangePhase.PLAN, McpChangePhase.PREPARE_APPLY, McpChangePhase.APPLY), phases)
        assertFalse("plan" in output)
        phases.clear()
        val verbose =
            tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent(verbose = true)).jsonObject)
        val full = Json.parseToJsonElement(verbose.document.value).jsonObject
        assertTrue("plan" in full)
        assertEquals(output.getValue("application"), full.getValue("application"))
        assertEquals(listOf(McpChangePhase.PLAN, McpChangePhase.PREPARE_APPLY, McpChangePhase.APPLY), phases)
    }

    @Test
    fun `replace body uses the same bounded live mutation phases`() {
        val example =
            PublicToolContract.examples(PublicToolIdentity.REPLACE_BODY).examples.getValue("exactTarget").value
        val admitted = PublicToolContract.admit(PublicToolIdentity.REPLACE_BODY, example)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
                { phase, arguments ->
                    phases += phase
                    if (phase == McpChangePhase.PLAN) {
                        val intent = arguments.getValue("intent").jsonObject
                        assertEquals("replace-body", intent.getValue("kind").jsonPrimitive.content)
                        assertEquals("{ return Unit }", intent.getValue("body").jsonPrimitive.content)
                    }
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

        val result = tool.invoke((admitted as Refinement.Refined).value)
        assertInstanceOf(CliExit.Complete::class.java, result)
        assertTrue(
            McpStructuredResults.validates("replace_body", Json.parseToJsonElement(result.document.value).jsonObject)
        )
        assertEquals(listOf(McpChangePhase.PLAN, McpChangePhase.PREPARE_APPLY, McpChangePhase.APPLY), phases)
    }

    @Test
    fun `unverified apply attempts recovery in the same call and preserves both outcomes`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
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

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        assertEquals("rejected", output.getValue("status").jsonPrimitive.content)
        assertTrue(McpStructuredResults.validates("add_declaration", output))
        val error = output.getValue("error").jsonObject
        assertEquals("APPLY_UNVERIFIED", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            "recovery_required",
            error.getValue("application").jsonObject.getValue("state").jsonPrimitive.content,
        )
        assertEquals("rolled_back", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(McpChangePhase.RECOVER, phases.last())
    }

    @Test
    fun `lost apply response triggers recovery and cannot claim verified success`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
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

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        val output = Json.parseToJsonElement(result.document.value).jsonObject
        val error = output.getValue("error").jsonObject
        assertEquals("APPLY_UNVERIFIED", error.getValue("code").jsonPrimitive.content)
        assertEquals("rolled_back", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(McpChangePhase.RECOVER, phases.last())
    }

    @Test
    fun `cancellation during apply propagates without claiming write outcome`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
                { phase, _ ->
                    phases += phase
                    when (phase) {
                        McpChangePhase.PLAN -> complete(TestPlan())
                        McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                        McpChangePhase.APPLY -> throw CancellationException("write outcome unknown")
                        McpChangePhase.PREPARE_RECOVER,
                        McpChangePhase.RECOVER -> error("caller owns cancellation settlement")
                    }
                },
                { "signed-for-${it.operation}" },
            )

        assertThrows(CancellationException::class.java) {
            tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        }
        assertEquals(listOf(McpChangePhase.PLAN, McpChangePhase.PREPARE_APPLY, McpChangePhase.APPLY), phases)
    }

    @Test
    fun `qualified recovery remains unavailable even with a parseable document`() {
        val tool =
            McpSingleChangeTool(
                root,
                { phase, _ ->
                    when (phase) {
                        McpChangePhase.PLAN -> complete(TestPlan())
                        McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                        McpChangePhase.APPLY -> qualified(TestUnverifiedApplication())
                        McpChangePhase.PREPARE_RECOVER -> complete(challenge("CHANGE_RECOVER"))
                        McpChangePhase.RECOVER -> qualified(TestRecoveryRequired())
                    }
                },
                { "signed-for-${it.operation}" },
            )

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        val error = Json.parseToJsonElement(result.document.value).jsonObject.getValue("error").jsonObject
        assertEquals("RECOVERY_UNAVAILABLE", error.getValue("code").jsonPrimitive.content)
        assertEquals("recovery_required", error.getValue("recovery").jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `rejected recovery remains unavailable even with a parseable document`() {
        val tool =
            McpSingleChangeTool(
                root,
                { phase, _ ->
                    when (phase) {
                        McpChangePhase.PLAN -> complete(TestPlan())
                        McpChangePhase.PREPARE_APPLY -> complete(challenge("CHANGE_APPLY"))
                        McpChangePhase.APPLY -> qualified(TestUnverifiedApplication())
                        McpChangePhase.PREPARE_RECOVER -> complete(challenge("CHANGE_RECOVER"))
                        McpChangePhase.RECOVER -> CliExit.OperationRejected(document(TestRecoveryRejection()))
                    }
                },
                { "signed-for-${it.operation}" },
            )

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        val error = Json.parseToJsonElement(result.document.value).jsonObject.getValue("error").jsonObject
        assertEquals("RECOVERY_UNAVAILABLE", error.getValue("code").jsonPrimitive.content)
        assertEquals("rejected", error.getValue("recovery").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `planning rejection cannot enter the write path`() {
        val phases = mutableListOf<McpChangePhase>()
        val tool =
            McpSingleChangeTool(
                root,
                { phase, _ ->
                    phases += phase
                    CliExit.OperationRejected(document(TestPlanningRejection()))
                },
                { error("no challenge expected") },
            )

        val result = tool.invoke(Json { encodeDefaults = true }.encodeToJsonElement(TestIntent()).jsonObject)
        assertInstanceOf(CliExit.OperationRejected::class.java, result)
        assertEquals(listOf(McpChangePhase.PLAN), phases)
        assertEquals(
            "PLANNING_REJECTED",
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

@Serializable
private data class TestIntent(
    val exactTarget: String = "exact-ref",
    val declaration: String = "fun added() = Unit",
    val verbose: Boolean = false,
)

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

@Serializable private data class TestRecovery(val status: String = "complete", val state: String = "rolled_back")

@Serializable
private data class TestRecoveryRequired(val status: String = "qualified", val state: String = "recovery_required")

@Serializable
private data class TestRecoveryRejection(val status: String = "rejected", val reason: String = "journal_unavailable")

@Serializable private data class TestPlanningRejection(val status: String = "rejected", val reason: String = "stale")
