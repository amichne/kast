package io.github.amichne.kast.idea.backend

import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.testPublishedWorkspaceGeneration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastRuntimeReadinessTest {
    @Test
    fun `IDEA indexing makes mutation readiness in progress despite a ready publication`() {
        val readiness = kastRuntimeReadiness(
            KastRuntimeReadinessObservation(
                admission = IdeaIndexSemanticAdmission.Status.Ready(testPublishedWorkspaceGeneration()),
                model = IdeaModelReadinessObservation.Indexing.fromDiscoveredModuleCount(1),
            ),
        )

        assertTrue(readiness.semanticGraph is RuntimeReadinessLane.Ready)
        assertTrue(readiness.model is RuntimeReadinessLane.InProgress)
        assertTrue(readiness.mutation is RuntimeReadinessLane.InProgress)
    }
}
