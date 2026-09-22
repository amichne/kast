package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.ControlFailure
import io.github.amichne.kast.appserver.runtime.ControlResult
import io.github.amichne.kast.appserver.runtime.DaemonManagement
import io.github.amichne.kast.appserver.runtime.DaemonSessionInspection
import io.github.amichne.kast.appserver.runtime.DaemonSessions
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DaemonControlProtocolTest {
    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")
    private val request =
        DaemonManagementRequest.Control(
            target,
            ControlOperation.CLAIM,
            "thread-1",
            "00000000-0000-0000-0000-000000000001",
        )

    @Test
    fun `control acknowledgment must preserve every admitted identity and operation`() {
        val accepted =
            DaemonManagementResponse.Controlled(target, request.operation, request.threadId, request.connectionId)
        assertEquals(Refinement.Refined(accepted), admitDaemonControl(accepted, request))
        val rejected =
            listOf(
                accepted.copy(target = target.copy(installationId = "other")),
                accepted.copy(target = target.copy(stateEpoch = "other")),
                accepted.copy(target = target.copy(serviceGeneration = "other")),
                accepted.copy(target = target.copy(configurationIdentity = "other")),
                accepted.copy(operation = ControlOperation.RELEASE),
                accepted.copy(threadId = "thread-2"),
                accepted.copy(connectionId = "00000000-0000-0000-0000-000000000002"),
                DaemonManagementResponse.Sessions(target, DaemonSessionInspection.Pending),
            )
        rejected.forEach {
            assertEquals(
                Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED)),
                admitDaemonControl(it, request),
            )
        }
    }

    @Test
    fun `encoded control contract retains required version discriminator and finite failures`() {
        val json = DaemonManagementProtocol.json
        val encoded = json.encodeToJsonElement(DaemonManagementRequest.serializer(), request).jsonObject
        assertEquals(setOf("type", "target", "operation", "threadId", "connectionId", "version"), encoded.keys)
        assertEquals("control", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("1", encoded.getValue("version").jsonPrimitive.content)
        assertEquals("CLAIM", encoded.getValue("operation").jsonPrimitive.content)
        val controlled =
            json
                .encodeToJsonElement(
                    DaemonManagementResponse.serializer(),
                    DaemonManagementResponse.Controlled(
                        target,
                        request.operation,
                        request.threadId,
                        request.connectionId,
                    ),
                )
                .jsonObject
        assertEquals(setOf("type", "target", "operation", "threadId", "connectionId"), controlled.keys)
        assertEquals("controlled", controlled.getValue("type").jsonPrimitive.content)
        ControlFailure.entries.forEach { failure ->
            val reason = DaemonManagementRejection.Control(failure)
            val response = DaemonManagementResponse.Rejected(reason)
            val document = json.encodeToJsonElement(DaemonManagementResponse.serializer(), response).jsonObject
            assertEquals(setOf("type", "reason"), document.keys)
            assertEquals("rejected", document.getValue("type").jsonPrimitive.content)
            val encodedReason = document.getValue("reason").jsonObject
            assertEquals(setOf("type", "failure"), encodedReason.keys)
            assertEquals("control", encodedReason.getValue("type").jsonPrimitive.content)
            assertEquals(failure.name, encodedReason.getValue("failure").jsonPrimitive.content)
            assertEquals(Refinement.Rejected(reason), admitDaemonControl(response, request))
        }
    }

    @Test
    fun `foreign session target and invalid controller IDs reject before touching session owner`() {
        val sessions =
            object : DaemonSessions {
                override fun inspectSessions(): DaemonSessionInspection = error("unexpected inspection")

                override fun controlSession(action: AppServerAction.Control): ControlResult =
                    error("unexpected control")
            }
        val management =
            DaemonManagement(target, { true }, { error("unexpected status") }, sessions) {
                error("unexpected enrollment")
            }
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED)
            ),
            management.execute(DaemonManagementRequest.Sessions(target.copy(serviceGeneration = "other"))),
        )
        assertEquals(
            DaemonManagementResponse.Rejected(DaemonManagementRejection.Control(ControlFailure.INVALID_ID)),
            management.execute(request.copy(connectionId = "invalid")),
        )
        val unknownOperation = jsonRequest().replace("CLAIM", "UNKNOWN")
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Protocol(DaemonManagementFailure.INVALID_REQUEST)
            ),
            DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(
                management.exchange(unknownOperation)
            ),
        )
    }

    private fun jsonRequest() = DaemonManagementProtocol.json.encodeToString<DaemonManagementRequest>(request)
}
