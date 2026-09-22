package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.runtime.DaemonManagement
import io.github.amichne.kast.appserver.runtime.UnavailableDaemonSessions
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonManagementTest {
    private val target =
        DaemonManagementTarget(
            "sha256:${"a".repeat(64)}",
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            "b".repeat(64),
        )
    private val status =
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

    @Test
    fun `update admission without a session owner retains unavailable failure`() {
        val management =
            DaemonManagement(target, { true }, { status }, UnavailableDaemonSessions) {
                error("update admission must not enroll a workspace")
            }
        val request = UpdateAdmissionRequest("prepare_update", 1, target, "c".repeat(64))
        val result = Json.parseToJsonElement(management.exchange(Json.encodeToString(request))).jsonObject
        assertEquals("rejected", result.getValue("type").jsonPrimitive.content)
        val reason = result.getValue("reason").jsonObject
        assertEquals("upgrade", reason.getValue("type").jsonPrimitive.content)
        assertEquals("UNAVAILABLE", reason.getValue("failure").jsonPrimitive.content)
    }

    /** Independent request boundary for the new private management operation. */
    @Serializable
    private data class UpdateAdmissionRequest(
        val type: String,
        val version: Int,
        val target: DaemonManagementTarget,
        val candidate: String,
    )

    @Test
    fun `foreign identity dimensions reject before enrollment or path observation`() {
        var effects = 0
        val management =
            DaemonManagement(target, { true }, { status }, UnavailableDaemonSessions) {
                effects++
                error("unexpected enrollment")
            }
        val foreign =
            listOf(
                target.copy(installationId = "other"),
                target.copy(stateEpoch = "other"),
                target.copy(serviceGeneration = "other"),
                target.copy(configurationIdentity = "other"),
            )
        foreign.forEach {
            assertEquals(
                rejection(DaemonManagementFailure.IDENTITY_REJECTED),
                management.execute(DaemonManagementRequest.RegisterWorkspace(it, "\u0000")),
            )
        }
        assertEquals(0, effects)
    }

    @Test
    fun `unknown version and lifecycle fence reject before effects`() {
        var effects = 0
        val management =
            DaemonManagement(
                target,
                { false },
                {
                    effects++
                    status
                },
                UnavailableDaemonSessions,
            ) {
                effects++
                error("unexpected enrollment")
            }
        assertEquals(
            rejection(DaemonManagementFailure.UNSUPPORTED_VERSION),
            management.execute(DaemonManagementRequest.Status(version = 2)),
        )
        assertEquals(
            rejection(DaemonManagementFailure.LIFECYCLE_TRANSITION),
            management.execute(DaemonManagementRequest.Status()),
        )
        assertEquals(
            rejection(DaemonManagementFailure.LIFECYCLE_TRANSITION),
            management.execute(DaemonManagementRequest.RegisterWorkspace(target, "/workspace")),
        )
        assertEquals(0, effects)
    }

    @Test
    fun `oversized and malformed commands reject before effects`() {
        var effects = 0
        val management =
            DaemonManagement(
                target,
                {
                    effects++
                    true
                },
                { status },
                UnavailableDaemonSessions,
            ) {
                effects++
                error("unexpected enrollment")
            }
        fun exchange(raw: String) =
            DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(management.exchange(raw))
        assertEquals(
            rejection(DaemonManagementFailure.CAPACITY_EXCEEDED),
            exchange(" ".repeat(DaemonManagementProtocol.maximumBytes + 1)),
        )
        assertEquals(
            rejection(DaemonManagementFailure.INVALID_REQUEST),
            exchange(Json.encodeToString("not a management request")),
        )
        assertEquals(0, effects)
    }

    @Test
    fun `registration preserves root revision and idempotence`(@TempDir directory: Path) {
        val root = Files.createDirectory(directory.resolve("workspace")).toRealPath()
        val store = WorkspaceEnrollmentStore(directory.toRealPath().resolve("registry/workspaces.json"))
        val management = DaemonManagement(target, { true }, { status }, UnavailableDaemonSessions) { store.enroll(it) }
        val request = DaemonManagementRequest.RegisterWorkspace(target, root.toString())
        val response = management.execute(request)
        assertTrue(response is DaemonManagementResponse.Registered)
        response as DaemonManagementResponse.Registered
        assertEquals(root.toString(), response.root)
        assertEquals(1L, response.revision)
        assertEquals(target, response.target)
        assertEquals(response, management.execute(request))
        val admitted =
            admitDaemonRegistration(encode(response), target, checkNotNull(CanonicalBrokerDirectory.admit(root)))
        assertTrue(admitted is Refinement.Refined)
        val snapshot = (store.snapshot() as WorkspaceRegistryRead.Read).snapshot
        assertEquals(1L, snapshot.revision.value)
        assertEquals(root, snapshot.workspaces.single().root.path)
        val document =
            Json.parseToJsonElement(Files.readString(directory.toRealPath().resolve("registry/workspaces.json")))
                .jsonObject
        assertEquals(setOf("schemaVersion", "revision", "roots"), document.keys)
        assertEquals("2", document.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("1", document.getValue("revision").jsonPrimitive.content)
        assertEquals(listOf(root.toString()), document.getValue("roots").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `enrollment failures retain exact codes`(@TempDir directory: Path) {
        val root = directory.toRealPath()
        EnrollmentFailure.entries.forEach { failure ->
            var calls = 0
            val management =
                DaemonManagement(target, { true }, { status }, UnavailableDaemonSessions) {
                    calls++
                    assertEquals(root, it.path)
                    Refinement.Rejected(failure)
                }
            val response = management.execute(DaemonManagementRequest.RegisterWorkspace(target, root.toString()))
            assertEquals(DaemonManagementResponse.Rejected(DaemonManagementRejection.Enrollment(failure)), response)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `invalid workspace path rejects without changing registry`(@TempDir directory: Path) {
        var calls = 0
        val management =
            DaemonManagement(target, { true }, { status }, UnavailableDaemonSessions) {
                calls++
                error("unexpected enrollment")
            }
        listOf("relative", "\u0000", directory.resolve("missing").toString()).forEach { root ->
            assertEquals(
                DaemonManagementResponse.Rejected(
                    DaemonManagementRejection.Enrollment(EnrollmentFailure.PATH_REJECTED)
                ),
                management.execute(DaemonManagementRequest.RegisterWorkspace(target, root)),
            )
        }
        assertEquals(0, calls)
        assertFalse(Files.exists(directory.resolve("registry")))
    }

    @Test
    fun `client rejects changed targets roots ids and invalid revisions`(@TempDir directory: Path) {
        val root = checkNotNull(CanonicalBrokerDirectory.admit(directory.toRealPath()))
        val valid =
            DaemonManagementResponse.Registered(target, WorkspaceRegistration(root).id.value, root.path.toString(), 1)
        listOf(
                valid.copy(target = target.copy(serviceGeneration = "other")),
                valid.copy(root = "/other"),
                valid.copy(workspaceId = "other"),
                valid.copy(revision = 0),
                valid.copy(revision = -1),
            )
            .forEach {
                assertEquals(
                    Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED)),
                    admitDaemonRegistration(encode(it), target, root),
                )
            }
        assertEquals(
            Refinement.Rejected(DaemonManagementRejection.Protocol(DaemonManagementFailure.RESPONSE_REJECTED)),
            admitDaemonRegistration(encode(DaemonManagementResponse.Status(status)), target, root),
        )
        assertEquals(
            Refinement.Rejected(DaemonManagementRejection.Enrollment(EnrollmentFailure.WRITE_REJECTED)),
            admitDaemonRegistration(
                encode(
                    DaemonManagementResponse.Rejected(
                        DaemonManagementRejection.Enrollment(EnrollmentFailure.WRITE_REJECTED)
                    )
                ),
                target,
                root,
            ),
        )
    }

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
                target,
                {
                    observations++
                    true
                },
                { status },
                UnavailableDaemonSessions,
            ) {
                error("unexpected enrollment")
            }
        val missing = Json.encodeToString(UnversionedRequest("status"))
        val unknown = Json.encodeToString(UnknownOperationRequest("unsupported", 1))
        listOf(missing, unknown).forEach {
            assertEquals(
                rejection(DaemonManagementFailure.INVALID_REQUEST),
                DaemonManagementProtocol.json.decodeFromString<DaemonManagementResponse>(management.exchange(it)),
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
                    encode(DaemonManagementResponse.Registered(target, "c".repeat(64), "/workspace", 3))
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
