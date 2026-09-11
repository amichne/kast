package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.change.apply.LiveChangeEffect
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedApprovalPreparationTest {
    private val fixture = HostedApprovalFixture()
    private val root = fixture.plan.basis.observation.reference.workspaceRoot

    @Test
    fun `strict request retains exact effect and canonical plan identity`() {
        val decoded =
            decodeHostedApprovalPreparation(
                    root,
                    """{"operation":"CHANGE_APPLY","planIdentity":"plan:${fixture.plan.planId.value}"}""",
                )
                .approvalRefined()
        val request = assertInstanceOf(HostedRequest.PrepareApproval::class.java, decoded)
        assertEquals(root, request.root)
        assertEquals(LiveChangeEffect.CHANGE_APPLY, request.effect)
    }

    @Test
    fun `duplicate unknown coerced trailing and unsupported preparation inputs reject`() {
        val normal = """{"operation":"CHANGE_APPLY","planIdentity":"plan:${fixture.plan.planId.value}"}"""
        listOf(
                normal.replace("\"operation\":", "\"operation\":\"CHANGE_RECOVER\",\"operation\":"),
                normal.dropLast(1) + ",\"approved\":true}",
                normal + "{}",
                normal.replace("CHANGE_APPLY", "CHANGE_PLAN"),
                normal.replace("\"CHANGE_APPLY\"", "true"),
                normal.replace("plan:", ""),
                normal.replace("\"operation\"", "operation"),
            )
            .forEach { malformed ->
                assertEquals(
                    Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST),
                    decodeHostedApprovalPreparation(root, malformed),
                    malformed,
                )
            }
    }

    @Test
    fun `recovery preview removes the planned declaration and retains recover operation`() {
        val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(fixture.key) }
        val response =
            Json.parseToJsonElement(
                    prepareHostedApprovalResponse(
                        plan = fixture.plan,
                        effect = LiveChangeEffect.CHANGE_RECOVER,
                        owner = fixture.owner,
                        approvals = approvals,
                    )
                )
                .jsonObject
        assertEquals(JsonPrimitive("CHANGE_RECOVER"), response["operation"])
        val diff = response.getValue("preview").jsonObject.getValue("diff").jsonPrimitive.content
        assertTrue(diff.lineSequence().any { it == "-fun added() = 1" })
        assertFalse(diff.lineSequence().any { it.startsWith("+") })
    }

    @Test
    fun `response projects stored plan diff and actual owner challenge only`() {
        val approvals = HostedChangeApprovals(fixture.owner) { Refinement.Refined(fixture.key) }
        val response =
            Json.parseToJsonElement(
                    prepareHostedApprovalResponse(
                        plan = fixture.plan,
                        effect = LiveChangeEffect.CHANGE_APPLY,
                        owner = fixture.owner,
                        approvals = approvals,
                    )
                )
                .jsonObject
        assertEquals(setOf("version", "operation", "root", "host", "planId", "challenge", "preview"), response.keys)
        assertEquals(JsonPrimitive(fixture.plan.planId.value), response["planId"])
        assertEquals(JsonPrimitive(root.value), response["root"])
        assertEquals(JsonPrimitive(fixture.owner.value.toString()), response["host"])
        val preview = response.getValue("preview").jsonObject
        assertEquals(JsonPrimitive("app/src/main/kotlin/sample/Service.kt"), preview["path"])
        assertTrue(preview.getValue("diff").jsonPrimitive.content.contains("fun added() = 1"))
    }
}
