package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiterIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LifecycleProjectReadinessObservationTest {
    private val identity =
        WorkspaceModelIdentity(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value,
            IdeReadHostLifetime.fromBoundary(UUID(0, 1)),
        )

    @Test
    fun `active model reload reports pending without another native model read`() {
        val running =
            WorkspaceRefreshInspectionDocument.Running(
                WorkspaceInspectionRefreshAttempt(
                    1,
                    WorkspaceInspectionRefreshEffect.MODEL_RELOAD,
                    1,
                    WorkspaceInspectionRefreshWaiterIdentity.Request("owned-import"),
                    emptyList(),
                ),
                emptyList(),
            )
        var reads = 0
        val result =
            observeLifecycleProjectReadiness(identity, running) {
                reads++
                WorkspaceCapabilityReadiness.Unavailable(
                    identity,
                    WorkspaceReadinessReason.OBSERVATION_FAILED,
                    WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                )
            }
        assertEquals(0, reads)
        assertEquals(
            WorkspaceCapabilityReadiness.Pending(
                identity,
                WorkspaceReadinessReason.NATIVE_WORK,
                WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
            ),
            result,
        )
    }

    @Test
    fun `idle refresh preserves the native failure and reads once`() {
        val rejected =
            WorkspaceCapabilityReadiness.Blocked(
                identity,
                WorkspaceReadinessReason.PROJECT_DISPOSED,
                WorkspaceReadinessNextAction.REOPEN_PROJECT,
            )
        var reads = 0
        val result =
            observeLifecycleProjectReadiness(identity, WorkspaceRefreshInspectionDocument.Idle) {
                reads++
                rejected
            }
        assertEquals(1, reads)
        assertSame(rejected, result)
    }

    @Test
    fun `inspection diagnostics encode each closed outcome with its refresh facts`() {
        val expected =
            mapOf(
                LifecycleProjectInspectionOutcome.READY to "READY",
                LifecycleProjectInspectionOutcome.PENDING to "PENDING",
                LifecycleProjectInspectionOutcome.UNAVAILABLE to "UNAVAILABLE",
                LifecycleProjectInspectionOutcome.BLOCKED to "BLOCKED",
            )
        assertEquals(LifecycleProjectInspectionOutcome.entries.toSet(), expected.keys)
        expected.forEach { (outcome, name) ->
            val encoded =
                Json.encodeToString(
                    LifecycleProjectInspectionObservation.serializer(),
                    LifecycleProjectInspectionObservation(WorkspaceRefreshInspectionDocument.Idle, outcome, 17),
                )
            val shape = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("refresh", "outcome", "elapsedMillis"), shape.keys)
            val refresh = shape.getValue("refresh").jsonObject
            assertEquals(setOf("type"), refresh.keys)
            assertEquals("idle", refresh.getValue("type").jsonPrimitive.content)
            assertEquals(name, shape.getValue("outcome").jsonPrimitive.content)
            assertEquals(17L, shape.getValue("elapsedMillis").jsonPrimitive.long)
        }
    }
}
