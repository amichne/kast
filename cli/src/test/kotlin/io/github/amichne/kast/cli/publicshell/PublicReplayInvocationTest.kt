package io.github.amichne.kast.cli.publicshell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicReplayInvocationTest {
    @Test
    fun `planning keeps its operation without executing a read`() {
        val receipt = replayAdmission(listOf("query", "plan", "--name", "OrderService"))
        assertEquals(ReplayAdmissionType.ADMITTED, receipt.type)
        val invocation = Json.encodeToJsonElement(receipt).jsonObject.getValue("invocation").jsonObject
        assertEquals("PLAN", invocation.getValue("operation").jsonPrimitive.content)
        assertEquals(0, receipt.nativeCalls)
    }

    @Test
    fun `current and explicitly current workspaces keep fixture ownership`() {
        for (options in listOf(emptyList(), listOf("--workspace", "."))) {
            val receipt = replayAdmission(listOf("query", "--name", "OrderService") + options)
            assertEquals(ReplayAdmissionType.ADMITTED, receipt.type)
            val invocation = Json.encodeToJsonElement(receipt).jsonObject.getValue("invocation").jsonObject
            assertEquals("EXECUTE", invocation.getValue("operation").jsonPrimitive.content)
            assertEquals("fixture", invocation.getValue("workspace").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun `foreign workspace selection stays distinct from valid arguments`() {
        val receipt = replayAdmission(listOf("query", "--name", "OrderService", "--workspace", "/another-worktree"))
        assertEquals(ReplayAdmissionType.ADMITTED, receipt.type)
        val workspace =
            Json.encodeToJsonElement(receipt)
                .jsonObject
                .getValue("invocation")
                .jsonObject
                .getValue("workspace")
                .jsonObject
        assertEquals("foreign", workspace.getValue("type").jsonPrimitive.content)
        assertEquals("/another-worktree", workspace.getValue("selection").jsonPrimitive.content)
        assertEquals(0, receipt.nativeCalls)
    }
}
