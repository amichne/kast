package io.github.amichne.kast.appserver

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DaemonManagementRuntimeProjectionTest {
    @Test
    fun `status encodes the passive runtime projection with an exact shape`() {
        val target =
            DaemonManagementTarget(
                "sha256:${"a".repeat(64)}",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                "b".repeat(64),
            )
        val status =
            CoordinatorStatusDocument(
                CoordinatorServiceState.READY,
                target.installationId,
                target.stateEpoch,
                target.serviceGeneration,
                target.configurationIdentity,
                0,
                0,
                emptyList(),
                CoordinatorHostAttachment.PENDING,
            )
        val response =
            DaemonManagementResponse.Status(status, ManagementRuntimeProjection("1.2.3", listOf("/workspace"), 2))
        val document =
            Json.parseToJsonElement(
                    DaemonManagementProtocol.json.encodeToString(DaemonManagementResponse.serializer(), response)
                )
                .jsonObject
        assertEquals(setOf("type", "coordinator", "projection"), document.keys)
        val runtime = document.getValue("projection").jsonObject
        assertEquals(setOf("loadedVersion", "activeWorkspaces", "liveConnections"), runtime.keys)
        assertEquals("1.2.3", runtime.getValue("loadedVersion").jsonPrimitive.content)
        assertEquals("/workspace", runtime.getValue("activeWorkspaces").jsonArray.single().jsonPrimitive.content)
        assertEquals(2, runtime.getValue("liveConnections").jsonPrimitive.content.toInt())
    }
}
