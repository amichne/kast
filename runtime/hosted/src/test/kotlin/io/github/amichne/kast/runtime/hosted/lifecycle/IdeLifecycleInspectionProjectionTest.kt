package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.HostedContractDocument
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshEffectResult
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshPort
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshRequestId
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshService
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStamp
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class IdeLifecycleInspectionProjectionTest {
    private val root =
        (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/detached-project")) as Refinement.Refined).value
    private val fixture = WorkspaceReadinessFixture(root, UUID(0, 1))

    @Test
    fun `public inspection stays reachable and names unresolved native attempt after every waiter deadline`() {
        var now = 0L
        var starts = 0
        val port =
            object : WorkspaceRefreshPort {
                override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
                    starts++
                }

                override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) {
                    starts++
                }

                override fun readiness(): WorkspaceCapabilityReadiness = fixture.ready()
            }
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10)
        val request = (WorkspaceRefreshRequestId.parse("lost-callback") as Refinement.Refined).value
        val stamp = (WorkspaceRefreshStamp.parse(3) as Refinement.Refined).value
        service.submit(request, WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp)
        val original = wire(service).getValue("projects").jsonArray.single().jsonObject
        val attempt =
            original.getValue("refresh").jsonObject.getValue("active").jsonObject.getValue("id").jsonPrimitive.content
        now = 10
        repeat(3) {
            val result = wire(service)
            assertEquals("inspected", result.getValue("type").jsonPrimitive.content)
            val project = result.getValue("projects").jsonArray.single().jsonObject
            val readiness = project.getValue("readiness").jsonObject
            assertEquals("pending", readiness.getValue("type").jsonPrimitive.content)
            val obstruction = readiness.getValue("obstruction").jsonObject
            assertEquals("NATIVE_WORK", obstruction.getValue("reason").jsonPrimitive.content)
            assertEquals("OBSERVE_SETTLEMENT", obstruction.getValue("nextAction").jsonPrimitive.content)
            assertEquals(
                "observed",
                obstruction.getValue("retainedModel").jsonObject.getValue("type").jsonPrimitive.content,
            )
            val refresh = project.getValue("refresh").jsonObject
            assertEquals("running", refresh.getValue("type").jsonPrimitive.content)
            assertEquals("OBSERVE_SETTLEMENT", refresh.getValue("nextAction").jsonPrimitive.content)
            val active = refresh.getValue("active").jsonObject
            assertEquals(attempt, active.getValue("id").jsonPrimitive.content)
            assertEquals("MODEL_RELOAD", active.getValue("effect").jsonPrimitive.content)
            assertEquals("lost-callback", active.getValue("initiator").jsonObject.getValue("id").jsonPrimitive.content)
            val waiter = active.getValue("waiters").jsonArray.single().jsonObject
            assertEquals(
                "DEADLINE_EXCEEDED",
                waiter.getValue("status").jsonObject.getValue("reason").jsonPrimitive.content,
            )
            assertFalse(project.toString().contains("authority"))
        }
        assertEquals(1, starts)
    }

    private fun wire(service: WorkspaceRefreshService) =
        Json.parseToJsonElement(
                Json { encodeDefaults = true }
                    .encodeToString(
                        IdeLifecycleResult.serializer(),
                        IdeLifecycleResult.Inspected(
                            "host",
                            "/detached-host",
                            "262.test",
                            listOf(
                                inspectionProjectDescription(
                                    IdeProjectDescription(
                                        IdeProjectTarget("host", UUID(0, 1).toString(), root.value),
                                        IdeProjectOwnership.BORROWED,
                                        0,
                                    ),
                                    fixture.ready(),
                                    service.inspection().inspectionDocument(),
                                )
                            ),
                            HostedCompatibilityDocument(
                                "262.test",
                                "262.test",
                                "test",
                                HostedContractDocument(
                                    runtimeProtocolIdentity = "test",
                                    operationRegistryDigest = "test",
                                    wireSchemaDigest = "test",
                                    capabilities = emptyList(),
                                ),
                            ),
                        ),
                    )
            )
            .jsonObject
}
