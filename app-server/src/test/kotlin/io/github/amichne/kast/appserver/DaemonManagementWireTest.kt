package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.DaemonManagement
import io.github.amichne.kast.appserver.runtime.UnavailableDaemonSessions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DaemonManagementWireTest {
    private val target =
        DaemonManagementTarget(
            installationId = "sha256:${"a".repeat(64)}",
            stateEpoch = "00000000-0000-0000-0000-000000000001",
            serviceGeneration = "00000000-0000-0000-0000-000000000002",
            configurationIdentity = "b".repeat(64),
        )
    private val status =
        CoordinatorStatusDocument(
            status = CoordinatorServiceState.READY,
            installationId = target.installationId,
            stateEpoch = target.stateEpoch,
            serviceGeneration = target.serviceGeneration,
            configurationIdentity = target.configurationIdentity,
            reservedMiB = 0,
            starting = 0,
            workers = emptyList(),
            hostAttachment = CoordinatorHostAttachment.PENDING,
        )

    @Test
    fun `wire projection emits required discriminators defaults and closed failure codes`() {
        val request =
            DaemonManagementProtocol.json.encodeToString(
                DaemonManagementRequest.serializer(),
                DaemonManagementRequest.Status(),
            )
        val document = Json.parseToJsonElement(request).jsonObject
        assertEquals(setOf("type", "version"), document.keys)
        assertEquals("status", document.getValue("type").jsonPrimitive.content)
        assertEquals("1", document.getValue("version").jsonPrimitive.content)
        val variants =
            listOf(
                DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED) to "protocol",
                DaemonManagementRejection.Coordinator(WorkerControlFailure.SERVICE_IDENTITY_REJECTED) to "coordinator",
                DaemonManagementRejection.Enrollment(EnrollmentFailure.DOCUMENT_REJECTED) to "enrollment",
            )
        variants.forEach { (reason, type) ->
            val rejected = Json.parseToJsonElement(encode(DaemonManagementResponse.Rejected(reason))).jsonObject
            assertEquals(setOf("type", "reason"), rejected.keys)
            assertEquals("rejected", rejected.getValue("type").jsonPrimitive.content)
            assertEquals(type, rejected.getValue("reason").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(setOf("type", "failure"), rejected.getValue("reason").jsonObject.keys)
        }
    }

    @Test
    fun `missing protocol version and unknown operation reject without observations`() {
        var observations = 0
        val management =
            DaemonManagement(
                target = target,
                available = {
                    observations++
                    true
                },
                status = { status },
                sessions = UnavailableDaemonSessions,
            ) {
                error("unexpected enrollment")
            }
        val missing = Json.encodeToString(UnversionedRequest("status"))
        val unknown = Json.encodeToString(UnknownOperationRequest("unsupported", 1))
        listOf(missing, unknown).forEach { request ->
            assertEquals(
                rejection(DaemonManagementFailure.INVALID_REQUEST),
                DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(management.exchange(request)),
            )
        }
        assertEquals(0, observations)
    }

    @Test
    fun `status and registration replies retain exact independent wire shapes`() {
        val statusDocument = Json.parseToJsonElement(encode(DaemonManagementResponse.Status(status))).jsonObject
        assertEquals(setOf("type", "coordinator"), statusDocument.keys)
        assertEquals("status", statusDocument.getValue("type").jsonPrimitive.content)
        assertEquals(
            "PENDING",
            statusDocument.getValue("coordinator").jsonObject.getValue("hostAttachment").jsonPrimitive.content,
        )
        val document =
            Json.parseToJsonElement(
                    encode(
                        DaemonManagementResponse.Registered(
                            target = target,
                            workspaceId = "c".repeat(64),
                            root = "/workspace",
                            revision = 3,
                        )
                    )
                )
                .jsonObject
        assertEquals(setOf("type", "target", "workspaceId", "root", "revision"), document.keys)
        assertEquals("registered", document.getValue("type").jsonPrimitive.content)
        assertEquals("/workspace", document.getValue("root").jsonPrimitive.content)
        assertEquals("c".repeat(64), document.getValue("workspaceId").jsonPrimitive.content)
        assertEquals("3", document.getValue("revision").jsonPrimitive.content)
        assertFalse(document.getValue("revision").jsonPrimitive.isString)
        assertEquals(
            setOf("installationId", "stateEpoch", "serviceGeneration", "configurationIdentity"),
            document.getValue("target").jsonObject.keys,
        )
        assertEquals(
            target.serviceGeneration,
            document.getValue("target").jsonObject.getValue("serviceGeneration").jsonPrimitive.content,
        )
    }

    @Serializable private data class UnversionedRequest(val type: String)

    @Serializable private data class UnknownOperationRequest(val type: String, val version: Int)

    private fun encode(response: DaemonManagementResponse) =
        DaemonManagementProtocol.json.encodeToString(DaemonManagementResponse.serializer(), response)

    private fun rejection(failure: DaemonManagementFailure) =
        DaemonManagementResponse.Rejected(DaemonManagementRejection.Protocol(failure))
}
