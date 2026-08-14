package io.github.amichne.kast.idea.backend

import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.spi.EdtHeartbeatTimeout
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAdmission
import io.github.amichne.kast.workspace.spi.RuntimeLivenessFailure
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastRuntimeReadinessTest {
    @Test
    fun `IDEA indexing makes mutation readiness in progress despite a ready publication`() {
        val readiness = kastRuntimeReadiness(
            KastRuntimeReadinessObservation(
                liveness = RuntimeLivenessAdmission.Live,
                admission = IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration()),
                model = IdeaModelReadinessObservation.Indexing.fromDiscoveredModuleCount(1),
            ),
        )

        assertTrue(readiness.semanticGraph is RuntimeReadinessLane.Ready)
        assertTrue(readiness.model is RuntimeReadinessLane.InProgress)
        assertTrue(readiness.mutation is RuntimeReadinessLane.InProgress)
    }

    @Test
    fun `frozen runtime does not manufacture stale source or graph blockers`() {
        val timeout = EdtHeartbeatTimeout.parse(250).refinedValue()
        val readiness = kastRuntimeReadiness(
            KastRuntimeReadinessObservation(
                liveness = RuntimeLivenessAdmission.Rejected(
                    RuntimeLivenessFailure.FrozenEventDispatchThread(timeout),
                ),
                admission = IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration()),
                model = IdeaModelReadinessObservation.Settled,
            ),
        )

        assertTrue(readiness.runtime is RuntimeReadinessLane.Blocked)
        assertTrue(readiness.model is RuntimeReadinessLane.Ready)
        assertTrue(readiness.semanticGraph is RuntimeReadinessLane.Ready)
        assertTrue(readiness.mutation is RuntimeReadinessLane.Blocked)
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
