package io.github.amichne.kast.idea.backend

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.contract.RuntimeProgressTiming
import io.github.amichne.kast.api.contract.RuntimeProgressWork
import io.github.amichne.kast.api.contract.RuntimeReadiness
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.RuntimeReadinessProgress
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission

internal sealed interface IdeaModelReadinessObservation {
    data object Settled : IdeaModelReadinessObservation

    @ConsistentCopyVisibility
    data class Indexing private constructor(
        val discoveredModules: NonNegativeInt,
    ) : IdeaModelReadinessObservation {
        companion object {
            /**
             * Proof transition: `Int -> IdeaModelReadinessObservation.Indexing`.
             *
             * Establishes a non-negative module count from the IntelliJ module
             * array boundary. The returned observation, not the raw count, flows
             * into runtime-readiness derivation.
             */
            fun fromDiscoveredModuleCount(count: Int): Indexing = Indexing(NonNegativeInt(count))
        }
    }

    companion object {
        /**
         * Proof transition: `(Boolean, Int) -> IdeaModelReadinessObservation`.
         *
         * Consumes IntelliJ's raw dumb-mode bit and module-array size at the
         * backend boundary. The output is a closed settled state or an indexing
         * state carrying a non-negative count; neither primitive flows inward.
         */
        fun fromIdeaState(isIndexing: Boolean, discoveredModuleCount: Int): IdeaModelReadinessObservation =
            if (isIndexing) Indexing.fromDiscoveredModuleCount(discoveredModuleCount) else Settled
    }
}

internal data class KastRuntimeReadinessObservation(
    val admission: IdeaIndexSemanticAdmission.Status,
    val model: IdeaModelReadinessObservation,
)

/**
 * Proof transition: `KastRuntimeReadinessObservation -> RuntimeReadiness`.
 *
 * Derives runtime, model, reference, and graph lanes from closed IDEA admission
 * and model observations, then constrains mutation readiness by both model and
 * graph authority. Raw IntelliJ state is admitted only while constructing
 * [KastRuntimeReadinessObservation].
 */
internal fun kastRuntimeReadiness(
    observation: KastRuntimeReadinessObservation,
): RuntimeReadiness {
    val model = when {
        observation.admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeReadinessLane.Blocked
        observation.model is IdeaModelReadinessObservation.Indexing -> RuntimeReadinessLane.InProgress(
            RuntimeReadinessProgress.derive(
                stage = RuntimeProgressStage.IDE_INDEXING,
                work = RuntimeProgressWork.pending(observation.model.discoveredModules),
                timing = RuntimeProgressTiming.unobserved(),
            ),
        )
        observation.admission is IdeaIndexSemanticAdmission.Status.Pending ->
            RuntimeReadinessLane.inProgress(RuntimeProgressStage.MODEL_SETTLEMENT)
        else -> RuntimeReadinessLane.Ready
    }
    val graph = when (observation.admission) {
        is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeReadinessLane.Blocked
        is IdeaIndexSemanticAdmission.Status.Pending ->
            RuntimeReadinessLane.inProgress(RuntimeProgressStage.SEMANTIC_GRAPH)
        is IdeaIndexSemanticAdmission.Status.Ready -> RuntimeReadinessLane.Ready
    }
    return RuntimeReadiness(
        runtime = when (observation.admission) {
            is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeReadinessLane.Blocked
            is IdeaIndexSemanticAdmission.Status.Pending,
            is IdeaIndexSemanticAdmission.Status.Ready,
            -> RuntimeReadinessLane.Ready
        },
        model = model,
        references = RuntimeReadinessLane.Blocked,
        semanticGraph = graph,
        mutation = when (model) {
            RuntimeReadinessLane.Ready -> graph
            RuntimeReadinessLane.Blocked -> RuntimeReadinessLane.Blocked
            is RuntimeReadinessLane.InProgress -> model
        },
    )
}
