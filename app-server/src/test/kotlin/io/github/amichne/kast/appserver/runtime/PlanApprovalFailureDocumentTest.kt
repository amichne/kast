package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlanApprovalFailureDocumentTest {
    private val request = Json.encodeToJsonElement(Request(17)).jsonObject
    private val root = CanonicalRoot(Path.of("/workspace"))

    @Test
    fun `every approval failure keeps its existing wire name and omits workspace`() {
        HostedPlanApprovalFailure.entries.forEach { failure ->
            val payload = payload(failure)
            assertEquals(setOf("status", "failure"), payload.keys)
            assertEquals("PLAN_APPROVAL_${failure.name}", payload.getValue("failure").jsonPrimitive.content)
        }
    }

    @Test
    fun `workspace admission and each operation cause retain their independent encoded shape`() {
        val admission =
            payload(
                    HostedPlanApprovalRejection.Workspace(
                        WorkspaceDemandFailure.Admission(root, WorkspacePreparationFailure.CAPACITY_EXCEEDED)
                    )
                )
                .getValue("workspace")
                .jsonObject
        assertEquals(setOf("type", "root", "failure"), admission.keys)
        assertEquals("admission", admission.getValue("type").jsonPrimitive.content)
        assertEquals("/workspace", admission.getValue("root").jsonPrimitive.content)
        assertEquals("CAPACITY_EXCEEDED", admission.getValue("failure").jsonPrimitive.content)
        val id = WorkspacePreparationId.fresh()
        listOf(
                Triple(
                    WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
                    "preparation",
                    "failure" to "DEADLINE_EXCEEDED",
                ),
                Triple(
                    WorkspaceDemandCause.Lifecycle(IdeLifecycleFailure.TRUST_REQUIRED),
                    "lifecycle",
                    "reason" to "TRUST_REQUIRED",
                ),
                Triple(WorkspaceDemandCause.HostChanged, "host_changed", null),
            )
            .forEach { (cause, kind, detail) ->
                val result =
                    payload(HostedPlanApprovalRejection.Workspace(WorkspaceDemandFailure.Operation(id, root, cause)))
                assertEquals("WORKSPACE_PREPARATION_REJECTED", result.getValue("failure").jsonPrimitive.content)
                val operation = result.getValue("workspace").jsonObject
                assertEquals(setOf("type", "requestId", "root", "cause"), operation.keys)
                assertEquals("operation", operation.getValue("type").jsonPrimitive.content)
                assertEquals(id.value.toString(), operation.getValue("requestId").jsonPrimitive.content)
                assertEquals("/workspace", operation.getValue("root").jsonPrimitive.content)
                val encoded = operation.getValue("cause").jsonObject
                assertEquals(kind, encoded.getValue("type").jsonPrimitive.content)
                assertEquals(if (detail == null) setOf("type") else setOf("type", detail.first), encoded.keys)
                if (detail != null) assertEquals(detail.second, encoded.getValue(detail.first).jsonPrimitive.content)
            }
    }

    private fun payload(failure: HostedPlanApprovalRejection): kotlinx.serialization.json.JsonObject {
        val reply = Json.parseToJsonElement(planApprovalFailure(request, failure)).jsonObject
        assertEquals(setOf("id", "result"), reply.keys)
        assertEquals("17", reply.getValue("id").jsonPrimitive.content)
        val result = reply.getValue("result").jsonObject
        assertEquals(setOf("success", "contentItems"), result.keys)
        assertEquals("false", result.getValue("success").jsonPrimitive.content)
        val item = result.getValue("contentItems").jsonArray.single().jsonObject
        assertEquals(setOf("text", "type"), item.keys)
        assertEquals("inputText", item.getValue("type").jsonPrimitive.content)
        val payload = Json.parseToJsonElement(item.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals("rejected", payload.getValue("status").jsonPrimitive.content)
        return payload
    }

    @Serializable private data class Request(val id: Int)
}
