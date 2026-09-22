@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.DaemonManagement
import io.github.amichne.kast.appserver.runtime.ManagedDaemonWorkspacePreparation
import io.github.amichne.kast.appserver.runtime.UnavailableDaemonSessions
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationState
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonWorkspacePreparationTest {
    private val target = DaemonManagementTarget("installation", "epoch", "generation", "configuration")

    @Test
    fun `preparation RPC returns stable identity and passive status retains exact readiness`(@TempDir directory: Path) =
        runTest {
            val root = directory.toRealPath()
            Files.writeString(root.resolve("settings.gradle.kts"), "")
            val native =
                IdeProjectTarget(
                    "00000000-0000-0000-0000-000000000001",
                    "00000000-0000-0000-0000-000000000002",
                    root.toString(),
                )
            val calls = mutableListOf<WorkspaceLifecycleRequest>()
            val owner =
                WorkspacePreparations(
                    this,
                    { request ->
                        calls += request
                        IdeLifecycleResult.Opened(native)
                    },
                )
            val management = management(owner)
            val first =
                management.exchangeRequest(DaemonManagementRequest.PrepareWorkspace(target, root.toString()))
                    as DaemonManagementResponse.WorkspacePreparation
            assertTrue(first.preparation.outcome is WorkspacePreparationState.Pending)
            assertEquals(
                first,
                management.exchangeRequest(DaemonManagementRequest.PrepareWorkspace(target, root.toString())),
            )
            runCurrent()
            val status =
                management.exchangeRequest(
                    DaemonManagementRequest.WorkspacePreparationStatus(target, first.preparation.requestId)
                ) as DaemonManagementResponse.WorkspacePreparation
            assertEquals(first.preparation.requestId, status.preparation.requestId)
            assertEquals(root.toString(), status.preparation.root)
            assertEquals(WorkspacePreparationState.Completed(native), status.preparation.outcome)
            assertEquals(listOf(WorkspaceLifecycleRequest.Open(root.toString(), first.preparation.requestId)), calls)
            assertEquals(
                status,
                management.exchangeRequest(
                    DaemonManagementRequest.WorkspacePreparationStatus(target, first.preparation.requestId)
                ),
            )
            assertEquals(1, calls.size)
            assertEncodedCompletion(status)
            owner.close()
        }

    @Test
    fun `foreign target and invalid IDs reject before filesystem or lifecycle effects`() = runTest {
        val owner = WorkspacePreparations(this, { error("unexpected lifecycle exchange") })
        val management = management(owner)
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Protocol(DaemonManagementFailure.IDENTITY_REJECTED)
            ),
            management.exchangeRequest(
                DaemonManagementRequest.PrepareWorkspace(target.copy(stateEpoch = "foreign"), "\u0000")
            ),
        )
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Preparation(WorkspacePreparationFailure.IDENTITY_REJECTED)
            ),
            management.exchangeRequest(DaemonManagementRequest.WorkspacePreparationStatus(target, "invalid")),
        )
        assertEquals(
            DaemonManagementResponse.Rejected(
                DaemonManagementRejection.Preparation(WorkspacePreparationFailure.ROOT_REJECTED)
            ),
            management.exchangeRequest(DaemonManagementRequest.PrepareWorkspace(target, "relative")),
        )
        runCurrent()
        owner.close()
    }

    private fun management(owner: WorkspacePreparations) =
        DaemonManagement(
            target,
            { true },
            { error("unexpected status") },
            UnavailableDaemonSessions,
            ManagedDaemonWorkspacePreparation(owner),
        ) {
            error("unexpected enrollment")
        }

    private fun assertEncodedCompletion(status: DaemonManagementResponse.WorkspacePreparation) {
        val encoded =
            DaemonManagementProtocol.json.encodeToJsonElement(DaemonManagementResponse.serializer(), status).jsonObject
        assertEquals(setOf("type", "target", "preparation"), encoded.keys)
        assertEquals("workspace_preparation", encoded.getValue("type").jsonPrimitive.content)
        val outcome = encoded.getValue("preparation").jsonObject.getValue("outcome").jsonObject
        assertEquals(setOf("type", "target"), outcome.keys)
        assertEquals("completed", outcome.getValue("type").jsonPrimitive.content)
    }

    private fun DaemonManagement.exchangeRequest(request: DaemonManagementRequest): DaemonManagementResponse =
        DaemonManagementProtocol.json.decodeFromString(exchange(DaemonManagementProtocol.json.encodeToString(request)))
}
