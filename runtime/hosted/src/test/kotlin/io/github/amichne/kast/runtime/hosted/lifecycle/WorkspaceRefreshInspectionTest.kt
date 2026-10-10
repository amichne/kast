package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionNextAction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshStatus
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiter
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiterIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage as InspectionStage
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshAttemptId
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshAttemptInspection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshFailure as NativeFailure
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshInspection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshNativeEffect
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshRejection as NativeRejection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshRequestId
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStage as NativeStage
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStamp
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStatus
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshWaiterIdentity
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshWaiterInspection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WorkspaceRefreshInspectionTest {
    private val request = (WorkspaceRefreshRequestId.parse("refresh-7") as Refinement.Refined).value
    private val stamp = (WorkspaceRefreshStamp.parse(3) as Refinement.Refined).value
    private val attempt =
        WorkspaceRefreshAttemptInspection(
            WorkspaceRefreshAttemptId.fromSequence(7),
            WorkspaceRefreshNativeEffect.MODEL_RELOAD,
            stamp,
            WorkspaceRefreshWaiterIdentity.Request(request),
            listOf(
                WorkspaceRefreshWaiterInspection(
                    WorkspaceRefreshWaiterIdentity.Request(request),
                    WorkspaceRefreshStatus.Failed(NativeFailure.DEADLINE_EXCEEDED),
                )
            ),
        )

    @Test
    fun `expired waiter never hides active native identity or suggests termination`() {
        val projected = WorkspaceRefreshInspection.Running(attempt, emptyList()).inspectionDocument()
        assertEquals(
            WorkspaceRefreshInspectionDocument.Running(
                WorkspaceInspectionRefreshAttempt(
                    7,
                    WorkspaceInspectionRefreshEffect.MODEL_RELOAD,
                    3,
                    WorkspaceInspectionRefreshWaiterIdentity.Request("refresh-7"),
                    listOf(
                        WorkspaceInspectionRefreshWaiter(
                            WorkspaceInspectionRefreshWaiterIdentity.Request("refresh-7"),
                            WorkspaceInspectionRefreshStatus.Failed(
                                WorkspaceInspectionRefreshFailure.DEADLINE_EXCEEDED
                            ),
                        )
                    ),
                ),
                emptyList(),
            ),
            projected,
        )
        val encoded = Json {
            encodeDefaults = true
        }
            .encodeToString(WorkspaceRefreshInspectionDocument.serializer(), projected)
        val shape = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "active", "queued", "nextAction"), shape.keys)
        assertEquals("running", shape.getValue("type").jsonPrimitive.content)
        assertEquals("OBSERVE_SETTLEMENT", shape.getValue("nextAction").jsonPrimitive.content)
        assertEquals("7", shape.getValue("active").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("MODEL_RELOAD", shape.getValue("active").jsonObject.getValue("effect").jsonPrimitive.content)
    }

    @Test
    fun `retirement preserves unresolved native attempt and observed admission remains a separate state`() {
        val retired =
            WorkspaceRefreshInspection.Retired(listOf(attempt)).inspectionDocument()
                as WorkspaceRefreshInspectionDocument.Retired
        assertEquals(7L, retired.unsettled.single().id)
        assertEquals(WorkspaceInspectionNextAction.ATTACH_HOST, retired.nextAction)
        val admitted =
            WorkspaceRefreshInspection.AwaitingAdmission(
                    listOf(
                        WorkspaceRefreshWaiterInspection(
                            WorkspaceRefreshWaiterIdentity.ReadPreparation,
                            WorkspaceRefreshStatus.Pending(NativeStage.ADMISSION),
                        )
                    )
                )
                .inspectionDocument() as WorkspaceRefreshInspectionDocument.AwaitingAdmission
        assertEquals(WorkspaceInspectionNextAction.OBSERVE_AGAIN, admitted.nextAction)
        assertEquals(WorkspaceInspectionRefreshWaiterIdentity.ReadPreparation, admitted.waiters.single().identity)
        assertEquals(
            WorkspaceInspectionRefreshStatus.Pending(InspectionStage.ADMISSION),
            admitted.waiters.single().status,
        )
    }

    @Test
    fun `all finite native failures rejections and stages remain exact`() {
        fun project(status: WorkspaceRefreshStatus): WorkspaceInspectionRefreshStatus =
            (WorkspaceRefreshInspection.AwaitingAdmission(
                        listOf(
                            WorkspaceRefreshWaiterInspection(WorkspaceRefreshWaiterIdentity.Request(request), status)
                        )
                    )
                    .inspectionDocument() as WorkspaceRefreshInspectionDocument.AwaitingAdmission)
                .waiters
                .single()
                .status
        NativeFailure.entries.forEach { failure ->
            val projected =
                assertInstanceOf(
                    WorkspaceInspectionRefreshStatus.Failed::class.java,
                    project(WorkspaceRefreshStatus.Failed(failure)),
                )
            assertEquals(failure.name, projected.reason.name)
        }
        NativeRejection.entries.forEach { rejection ->
            val projected =
                assertInstanceOf(
                    WorkspaceInspectionRefreshStatus.Rejected::class.java,
                    project(WorkspaceRefreshStatus.Rejected(rejection)),
                )
            assertEquals(rejection.name, projected.reason.name)
        }
        NativeStage.entries.forEach { stage ->
            val projected =
                assertInstanceOf(
                    WorkspaceInspectionRefreshStatus.Pending::class.java,
                    project(WorkspaceRefreshStatus.Pending(stage)),
                )
            assertEquals(stage.name, projected.stage.name)
        }
    }
}
