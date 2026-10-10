package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeProjectOwnership
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochStage
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionModelIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionNextAction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionObstruction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadOperation
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadinessReason
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiterIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRetainedModel
import io.github.amichne.kast.protocol.contract.WorkspaceReadinessInspectionDocument
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspaceReadinessInspectionTest {
    private val root =
        (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/detached-project")) as Refinement.Refined).value
    private val fixture = WorkspaceReadinessFixture(root, UUID(0, 1))
    private val json = Json { encodeDefaults = true }

    @Test
    fun `ready inspection reports current native identity and opaque evidence without granting semantic authority`() {
        val actual = fixture.ready().inspectionDocument()
        assertEquals(
            WorkspaceReadinessInspectionDocument.Ready(
                WorkspaceInspectionModelIdentity(root.value, UUID(0, 1).toString())
            ),
            actual,
        )
        val encoded = json.encodeToString(WorkspaceReadinessInspectionDocument.serializer(), actual)
        val shape = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("type", "identity", "capability", "epoch"), shape.keys)
        assertEquals("ready", shape.getValue("type").jsonPrimitive.content)
        assertEquals("MODEL_PREPARATION", shape.getValue("capability").jsonPrimitive.content)
        assertEquals("OBSERVED_OPAQUE_EPOCH", shape.getValue("epoch").jsonPrimitive.content)
        assertFalse(encoded.contains("authority"))
    }

    @Test
    fun `pending inspection retains observed model and original active read identity`() {
        val operation = UUID(0, 2)
        val current =
            WorkspaceCapabilityReadiness.Pending(
                fixture.identity,
                WorkspaceReadinessReason.NATIVE_WORK,
                WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                WorkspaceReadinessDetail.UnsettledReads(
                    fixture.ready(),
                    listOf(WorkspaceReadOperationIdentity.Traced(operation)),
                ),
            )
        val actual = current.inspectionDocument()
        assertEquals(
            WorkspaceReadinessInspectionDocument.Pending(
                WorkspaceInspectionModelIdentity(root.value, UUID(0, 1).toString()),
                WorkspaceInspectionObstruction(
                    WorkspaceInspectionReadinessReason.NATIVE_WORK,
                    WorkspaceInspectionNextAction.OBSERVE_SETTLEMENT,
                    WorkspaceInspectionRetainedModel.Observed(
                        WorkspaceInspectionModelIdentity(root.value, UUID(0, 1).toString())
                    ),
                    listOf(WorkspaceInspectionReadOperation.Traced(operation.toString())),
                ),
            ),
            actual,
        )
        val shape =
            Json.parseToJsonElement(json.encodeToString(WorkspaceReadinessInspectionDocument.serializer(), actual))
                .jsonObject
        assertEquals("pending", shape.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("reason", "nextAction", "retainedModel", "activeReads", "epochRejection"),
            shape.getValue("obstruction").jsonObject.keys,
        )
    }

    @Test
    fun `unavailable and retained rejection preserve native epoch failure and stage`() {
        val rejection =
            WorkspaceCapabilityReadiness.Unavailable(
                fixture.identity,
                WorkspaceReadinessReason.EPOCH_UNAVAILABLE,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                WorkspaceReadinessDetail.EpochRejected(
                    ProjectReadEpochObservationFailure.ObservationFailed(ProjectReadEpochObservationStage.PSI)
                ),
            )
        val expected = WorkspaceInspectionEpochRejection.ObservationFailed(WorkspaceInspectionEpochStage.PSI)
        val direct = rejection.inspectionDocument() as WorkspaceReadinessInspectionDocument.Unavailable
        assertEquals(expected, direct.obstruction.epochRejection)
        val waiting =
            WorkspaceCapabilityReadiness.Pending(
                    fixture.identity,
                    WorkspaceReadinessReason.NATIVE_WORK,
                    WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                    WorkspaceReadinessDetail.UnsettledReads(rejection, emptyList()),
                )
                .inspectionDocument() as WorkspaceReadinessInspectionDocument.Pending
        val retained = waiting.obstruction.retainedModel as WorkspaceInspectionRetainedModel.Rejected
        assertEquals(expected, retained.epochRejection)
    }

    @Test
    fun `every finite readiness reason and action survives transport projection`() {
        WorkspaceReadinessReason.entries.forEach { reason ->
            WorkspaceReadinessNextAction.entries.forEach { action ->
                val projected =
                    WorkspaceCapabilityReadiness.Blocked(fixture.identity, reason, action).inspectionDocument()
                        as WorkspaceReadinessInspectionDocument.Blocked
                assertEquals(reason.name, projected.obstruction.reason.name)
                assertEquals(action.name, projected.obstruction.nextAction.name)
            }
        }
    }

    @Test
    fun `registration snapshot retains admitted root but never manufactures readiness`() {
        val state = IdeLifecycleState(UUID(0, 3))
        val project = UUID(0, 1)
        val target = state.observe(project, root, IdeProjectOwnership.BORROWED)
        val selection = state.inspectionSelections().single()
        assertEquals(root, selection.root)
        assertEquals(project, selection.project)
        assertEquals(target, selection.description.target)
        assertSame(WorkspaceReadinessInspectionDocument.Unknown, selection.description.readiness)
        assertEquals(state.inspect().single(), selection.description)
    }

    @Test
    fun `unresolved refresh keeps inspection reachable and retains available model evidence`() {
        val ready = fixture.ready()
        val running = runningRefresh()
        val projected = inspectionReadinessDocument(ready, running) as WorkspaceReadinessInspectionDocument.Pending
        assertEquals(WorkspaceInspectionReadinessReason.NATIVE_WORK, projected.obstruction.reason)
        assertEquals(WorkspaceInspectionNextAction.OBSERVE_SETTLEMENT, projected.obstruction.nextAction)
        assertEquals(
            WorkspaceInspectionRetainedModel.Observed(
                WorkspaceInspectionModelIdentity(root.value, UUID(0, 1).toString())
            ),
            projected.obstruction.retainedModel,
        )
        val disposed =
            WorkspaceCapabilityReadiness.Blocked(
                fixture.identity,
                WorkspaceReadinessReason.PROJECT_DISPOSED,
                WorkspaceReadinessNextAction.REOPEN_PROJECT,
            )
        val terminal = inspectionReadinessDocument(disposed, running) as WorkspaceReadinessInspectionDocument.Blocked
        assertEquals(WorkspaceInspectionReadinessReason.PROJECT_DISPOSED, terminal.obstruction.reason)
    }

    @Test
    fun `previously observed model survives current epoch rejection and both native settlement overlays`() {
        val currentFailure =
            WorkspaceReadinessDetail.EpochRejected(ProjectReadEpochObservationFailure.GradleModelUnavailable)
        val rejected =
            WorkspaceCapabilityReadiness.Unavailable(
                fixture.identity,
                WorkspaceReadinessReason.EPOCH_UNAVAILABLE,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                WorkspaceReadinessDetail.PreviouslyObservedModel(fixture.ready(), currentFailure),
            )
        val retained =
            WorkspaceInspectionRetainedModel.Observed(
                WorkspaceInspectionModelIdentity(root.value, UUID(0, 1).toString())
            )
        val direct = rejected.inspectionDocument() as WorkspaceReadinessInspectionDocument.Unavailable
        assertEquals(WorkspaceInspectionReadinessReason.EPOCH_UNAVAILABLE, direct.obstruction.reason)
        assertEquals(retained, direct.obstruction.retainedModel)
        val epochFailure =
            WorkspaceInspectionEpochRejection.Rejected(WorkspaceInspectionEpochFailure.GRADLE_MODEL_UNAVAILABLE)
        assertEquals(epochFailure, direct.obstruction.epochRejection)
        val operation = UUID(0, 2)
        val readPending =
            WorkspaceCapabilityReadiness.Pending(
                fixture.identity,
                WorkspaceReadinessReason.NATIVE_WORK,
                WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                WorkspaceReadinessDetail.UnsettledReads(
                    rejected,
                    listOf(WorkspaceReadOperationIdentity.Traced(operation)),
                ),
            )
        val running = runningRefresh()
        val projected =
            inspectionReadinessDocument(readPending, running) as WorkspaceReadinessInspectionDocument.Pending
        assertEquals(retained, projected.obstruction.retainedModel)
        assertEquals(epochFailure, projected.obstruction.epochRejection)
        assertEquals(
            listOf(WorkspaceInspectionReadOperation.Traced(operation.toString())),
            projected.obstruction.activeReads,
        )
    }

    private fun runningRefresh() =
        WorkspaceRefreshInspectionDocument.Running(
            WorkspaceInspectionRefreshAttempt(
                1,
                WorkspaceInspectionRefreshEffect.MODEL_RELOAD,
                3,
                WorkspaceInspectionRefreshWaiterIdentity.Request("refresh-1"),
                emptyList(),
            ),
            emptyList(),
        )
}
