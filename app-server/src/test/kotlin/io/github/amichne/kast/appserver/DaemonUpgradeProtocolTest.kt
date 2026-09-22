package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.DaemonManagement
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeDocument
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeFailure
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeGate
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeObservation
import io.github.amichne.kast.appserver.runtime.DaemonUpgradeOutcome
import io.github.amichne.kast.appserver.runtime.DeferredBrokerFrontend
import io.github.amichne.kast.appserver.runtime.UpgradeBlocker
import io.github.amichne.kast.appserver.runtime.UpgradeCandidate
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DaemonUpgradeProtocolTest {
    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")
    private val candidate = "a".repeat(64)

    private val status =
        CoordinatorStatusDocument(
            CoordinatorServiceState.READY,
            "installation",
            "epoch",
            "generation",
            "configuration",
            0,
            0,
            emptyList(),
            CoordinatorHostAttachment.PENDING,
        )

    @Test
    fun `management seal blocks mutations preserves passive status and commits exact request`() = runBlocking {
        val frontend = DeferredBrokerFrontend { error("unexpected host qualification") }
        val management = DaemonManagement(target, { true }, { status }, frontend) { error("unexpected enrollment") }
        try {
            val sealed =
                management.execute(DaemonManagementRequest.PrepareUpdate(target, candidate))
                    as DaemonManagementResponse.Update
            assertInstanceOf(DaemonUpgradeDocument.Sealed::class.java, sealed.update)
            assertEquals(DaemonManagementResponse.Status(status), management.execute(DaemonManagementRequest.Status()))
            assertEquals(
                DaemonManagementResponse.Rejected(
                    DaemonManagementRejection.Upgrade(DaemonUpgradeFailure.ADMISSION_SEALED)
                ),
                management.execute(DaemonManagementRequest.RegisterWorkspace(target, "/unused")),
            )
            assertEquals(
                sealed,
                management.execute(DaemonManagementRequest.UpdateStatus(target, sealed.update.requestId)),
            )
            assertEquals(
                DaemonManagementResponse.Rejected(
                    DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED)
                ),
                management.execute(
                    DaemonManagementRequest.CommitUpdate(
                        target.copy(serviceGeneration = "foreign"),
                        sealed.update.requestId,
                    )
                ),
            )
            val committed =
                management.execute(DaemonManagementRequest.CommitUpdate(target, sealed.update.requestId))
                    as DaemonManagementResponse.Update
            assertInstanceOf(DaemonUpgradeDocument.Committed::class.java, committed.update)
            assertEquals(sealed.update.requestId, committed.update.requestId)
            assertEquals(
                committed,
                management.execute(DaemonManagementRequest.CommitUpdate(target, sealed.update.requestId)),
            )
            assertEquals(
                DaemonManagementResponse.Rejected(
                    DaemonManagementRejection.Upgrade(DaemonUpgradeFailure.ALREADY_COMMITTED)
                ),
                management.execute(DaemonManagementRequest.CancelUpdate(target, sealed.update.requestId)),
            )
        } finally {
            frontend.close()
        }
    }

    @Test
    fun `every update outcome encodes its discriminator and retained identity`() {
        val requestId = "00000000-0000-0000-0000-000000000001"
        val documents =
            listOf(
                "pending" to DaemonUpgradeDocument.Pending(requestId, candidate, listOf(UpgradeBlocker.ACTIVE_TURN)),
                "sealed" to DaemonUpgradeDocument.Sealed(requestId, candidate),
                "committed" to DaemonUpgradeDocument.Committed(requestId, candidate),
                "cancelled" to DaemonUpgradeDocument.Cancelled(requestId, candidate),
            )
        for ((kind, document) in documents) {
            val encoded =
                Json.parseToJsonElement(
                        DaemonManagementProtocol.json.encodeToString<DaemonManagementResponse>(
                            DaemonManagementResponse.Update(target, document)
                        )
                    )
                    .jsonObject
            assertEquals("update", encoded.getValue("type").jsonPrimitive.content)
            assertEquals(
                Json.encodeToJsonElement(DaemonManagementTarget.serializer(), target),
                encoded.getValue("target"),
            )
            val update = encoded.getValue("update").jsonObject
            assertEquals(kind, update.getValue("type").jsonPrimitive.content)
            assertEquals(requestId, update.getValue("requestId").jsonPrimitive.content)
            assertEquals(candidate, update.getValue("candidate").jsonPrimitive.content)
        }
    }

    @Test
    fun `gate observes finite pending sealed committed and rejected outcomes`() {
        val events = mutableListOf<DaemonUpgradeObservation>()
        val gate = DaemonUpgradeGate { events += it }
        val admitted = (UpgradeCandidate.admit(candidate) as Refinement.Refined).value
        val pending = (gate.prepare(admitted) { setOf(UpgradeBlocker.ACTIVE_TURN) } as Refinement.Refined).value
        gate.commit(pending.request.id)
        gate.prepare(admitted) { emptySet() }
        gate.commit(pending.request.id)
        assertEquals(
            listOf(
                DaemonUpgradeOutcome.Pending(listOf(UpgradeBlocker.ACTIVE_TURN)),
                DaemonUpgradeOutcome.Rejected(DaemonUpgradeFailure.NOT_QUIESCENT),
                DaemonUpgradeOutcome.Sealed,
                DaemonUpgradeOutcome.Committed,
            ),
            events.map { it.outcome },
        )
        for (event in events) {
            val encoded = Json.parseToJsonElement(event.toJson()).jsonObject
            assertEquals(setOf("event", "stage", "outcome"), encoded.keys)
            assertEquals("kast_daemon_upgrade", encoded.getValue("event").jsonPrimitive.content)
        }
    }
}
