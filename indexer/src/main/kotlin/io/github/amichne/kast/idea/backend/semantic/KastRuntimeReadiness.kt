package io.github.amichne.kast.idea.backend

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.contract.RuntimeReadiness
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.RuntimeReadinessProgress
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission

internal fun kastRuntimeReadiness(
    admission: IdeaIndexSemanticAdmission.Status,
    isDumb: Boolean,
    discoveredModules: Int,
): RuntimeReadiness {
    val model = when {
        admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeReadinessLane.Blocked
        isDumb -> RuntimeReadinessLane.InProgress(
            RuntimeReadinessProgress(
                stage = RuntimeProgressStage.IDE_INDEXING,
                completedUnits = 0,
                totalUnits = discoveredModules.toLong(),
                elapsedMillis = 0,
                noProgressMillis = 0,
            ),
        )
        admission is IdeaIndexSemanticAdmission.Status.Pending ->
            RuntimeReadinessLane.inProgress(RuntimeProgressStage.MODEL_SETTLEMENT)
        else -> RuntimeReadinessLane.Ready
    }
    val graph = when (admission) {
        is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeReadinessLane.Blocked
        is IdeaIndexSemanticAdmission.Status.Pending ->
            RuntimeReadinessLane.inProgress(RuntimeProgressStage.SEMANTIC_GRAPH)
        is IdeaIndexSemanticAdmission.Status.Ready -> RuntimeReadinessLane.Ready
    }
    return RuntimeReadiness(
        runtime = RuntimeReadinessLane.Ready,
        model = model,
        references = RuntimeReadinessLane.Blocked,
        semanticGraph = graph,
        mutation = graph,
    )
}
