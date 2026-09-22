package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.AppServerControlAdmission
import io.github.amichne.kast.appserver.ControlOperation
import io.github.amichne.kast.appserver.DaemonManagementFailure
import io.github.amichne.kast.appserver.DaemonManagementProtocol
import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.DaemonManagementRequest
import io.github.amichne.kast.appserver.DaemonManagementResponse
import io.github.amichne.kast.appserver.DaemonManagementTarget
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonControllerTest {
    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")

    @Test
    fun `daemon control preserves live session ownership without another upstream connection`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            val management =
                DaemonManagement(target, { true }, { error("unexpected status") }, fixture.hub) {
                    error("unexpected enrollment")
                }
            try {
                val owner = fixture.connect()
                fixture.bind(owner, "thread/start")
                val observer = fixture.connect()
                fixture.bind(observer, "thread/resume")
                val before =
                    management.apply(DaemonManagementRequest.Sessions(target)) as DaemonManagementResponse.Sessions
                val snapshot = before.inspection as DaemonSessionInspection.Prepared
                assertEquals(
                    DaemonManagementResponse.Controlled(
                        target,
                        ControlOperation.RELEASE,
                        "thread-1",
                        owner.session.id.value,
                    ),
                    management.apply(request(ControlOperation.RELEASE, owner.session.id.value)),
                )
                assertEquals(
                    DaemonManagementResponse.Controlled(
                        target,
                        ControlOperation.CLAIM,
                        "thread-1",
                        observer.session.id.value,
                    ),
                    management.apply(request(ControlOperation.CLAIM, observer.session.id.value)),
                )
                val after =
                    (management.apply(DaemonManagementRequest.Sessions(target)) as DaemonManagementResponse.Sessions)
                        .inspection as DaemonSessionInspection.Prepared
                assertEquals(observer.session.id.value, after.tasks.single().controller)
                assertNotEquals(snapshot.tasks.single().controllerLease, after.tasks.single().controllerLease)
                assertEquals(snapshot.connections, after.connections)
                assertTrue(owner.upstream.sent.tryReceive().isFailure)
                assertTrue(observer.upstream.sent.tryReceive().isFailure)
            } finally {
                fixture.hub.close()
            }
            assertEquals(DaemonSessionInspection.Closed, fixture.hub.inspectSessions())
        }

    @Test
    fun `rejected handoffs preserve the connected controller and lease`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        val management =
            DaemonManagement(target, { true }, { error("unexpected status") }, fixture.hub) {
                error("unexpected enrollment")
            }
        try {
            val owner = fixture.connect()
            fixture.bind(owner, "thread/start")
            val observer = fixture.connect()
            fixture.bind(observer, "thread/resume")
            val before = management.apply(DaemonManagementRequest.Sessions(target)) as DaemonManagementResponse.Sessions
            val snapshot = before.inspection as DaemonSessionInspection.Prepared
            assertEquals(
                setOf(owner.session.id.value, observer.session.id.value),
                snapshot.connections.map { it.id }.toSet(),
            )
            assertEquals(owner.session.id.value, snapshot.tasks.single().controller)
            assertEquals(DaemonTaskActivity.IDLE, snapshot.tasks.single().activity)
            assertRejectedOwnership(management, owner, observer)
            assertEquals(
                before,
                management.apply(DaemonManagementRequest.Sessions(target)),
                "rejections preserve controller and lease",
            )
        } finally {
            fixture.hub.close()
        }
    }

    private fun assertRejectedOwnership(management: DaemonManagement, owner: HubTestPeer, observer: HubTestPeer) {
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED)
            ),
            management.apply(
                request(ControlOperation.RELEASE, owner.session.id.value, target.copy(stateEpoch = "foreign"))
            ),
        )
        assertEquals(
            DaemonManagementResponse.Rejected(DaemonManagementRejection.Control(ControlFailure.CONTROLLER_CONFLICT)),
            management.apply(request(ControlOperation.CLAIM, observer.session.id.value)),
        )
        assertEquals(
            DaemonManagementResponse.Rejected(DaemonManagementRejection.Control(ControlFailure.CONNECTION_UNKNOWN)),
            management.apply(request(ControlOperation.CLAIM, "00000000-0000-0000-0000-000000000001")),
        )
    }

    private fun request(operation: ControlOperation, connection: String, identity: DaemonManagementTarget = target) =
        DaemonManagementRequest.Control(identity, operation, "thread-1", connection)

    private fun DaemonManagement.apply(request: DaemonManagementRequest): DaemonManagementResponse =
        DaemonManagementProtocol.json.decodeFromString(exchange(DaemonManagementProtocol.json.encodeToString(request)))

    @Test
    fun `native observer cannot release a controller by asserting its connection identity`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val owner = fixture.connect()
                fixture.bind(owner, "thread/start")
                val observer = fixture.connect()
                fixture.bind(observer, "thread/resume")
                val before = fixture.hub.inspectSessions()
                observer.session.accept(
                    DaemonManagementProtocol.json.encodeToString(
                        LegacyControlRequest(params = LegacyControlParams("thread-1", owner.session.id.value))
                    )
                )
                val result =
                    DaemonManagementProtocol.json.parseToJsonElement(observer.session.output.receive()).jsonObject
                assertEquals(
                    "UNSUPPORTED_OPERATION",
                    result.getValue("error").jsonObject.getValue("message").jsonPrimitive.content,
                )
                assertEquals(before, fixture.hub.inspectSessions())
                assertTrue(observer.upstream.sent.tryReceive().isFailure)
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `passive pending and closed frontend operations never prepare the host`() = runBlocking {
        val frontend = DeferredBrokerFrontend { error("unexpected host preparation") }
        val action =
            (AppServerAction.Control.admit(ControlOperation.CLAIM, "thread-1", "00000000-0000-0000-0000-000000000001")
                    as AppServerControlAdmission.Admitted)
                .action
        assertEquals(DaemonSessionInspection.Pending, frontend.inspectSessions())
        assertEquals(ControlResult.Rejected(ControlFailure.HOST_UNAVAILABLE), frontend.controlSession(action))
        frontend.close()
        assertEquals(DaemonSessionInspection.Closed, frontend.inspectSessions())
        assertEquals(ControlResult.Rejected(ControlFailure.HOST_UNAVAILABLE), frontend.controlSession(action))
    }
}

@Serializable
private data class LegacyControlRequest(
    val id: Int = 2,
    val method: String = "kast/appServer/control/release",
    val params: LegacyControlParams,
)

@Serializable private data class LegacyControlParams(val threadId: String, val connectionId: String)
