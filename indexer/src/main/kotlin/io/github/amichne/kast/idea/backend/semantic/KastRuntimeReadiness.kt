package io.github.amichne.kast.idea.backend

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.contract.RuntimeProgressTiming
import io.github.amichne.kast.api.contract.RuntimeProgressWork
import io.github.amichne.kast.api.contract.RuntimeReadiness
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.RuntimeReadinessProgress
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.workspace.spi.RuntimeLivenessAdmission

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
    val liveness: RuntimeLivenessAdmission,
    val admission: IdeaIndexSemanticAdmission.Status,
    val model: IdeaModelReadinessObservation,
)

/**
 * Proof transition: `KastRuntimeReadinessObservation -> RuntimeReadiness`.
 *
 * Derives runtime liveness independently from model, reference, and graph lanes, then
 * constrains mutation readiness by liveness, model, and graph authority. Raw IntelliJ state is
 * admitted only while constructing [KastRuntimeReadinessObservation].
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
        runtime = when (observation.liveness) {
            RuntimeLivenessAdmission.Live -> RuntimeReadinessLane.Ready
            is RuntimeLivenessAdmission.Rejected -> RuntimeReadinessLane.Blocked
        },
        model = model,
        references = RuntimeReadinessLane.Blocked,
        semanticGraph = graph,
        mutation = when (observation.liveness) {
            is RuntimeLivenessAdmission.Rejected -> RuntimeReadinessLane.Blocked
            RuntimeLivenessAdmission.Live -> when (model) {
                RuntimeReadinessLane.Ready -> graph
                RuntimeReadinessLane.Blocked -> RuntimeReadinessLane.Blocked
                is RuntimeReadinessLane.InProgress -> model
            }
        },
    )
}

internal suspend fun KastIndexerBackend.runtimeStatusEvidence(): RuntimeStatusResponse {
    val caps = capabilities()
    val isDumb = DumbService.isDumb(project)
    val liveness = runtimeLivenessAuthority.admit()
    val admission = workspaceSemanticReadAuthority.status()
    val state = when {
        liveness is RuntimeLivenessAdmission.Rejected -> RuntimeState.DEGRADED
        admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeState.DEGRADED
        isDumb || admission is IdeaIndexSemanticAdmission.Status.Pending -> RuntimeState.INDEXING
        else -> RuntimeState.READY
    }
    val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
    val modelObservation = IdeaModelReadinessObservation.fromIdeaState(isDumb, moduleNames.size)
    val baseReadiness = kastRuntimeReadiness(
        KastRuntimeReadinessObservation(
            liveness = liveness,
            admission = admission,
            model = modelObservation,
        ),
    )
    return RuntimeStatusResponse(
        state = state,
        backendName = caps.backendName,
        backendVersion = caps.backendVersion,
        workspaceRoot = caps.workspaceRoot,
        message = when {
            liveness is RuntimeLivenessAdmission.Rejected ->
                "IntelliJ runtime liveness is blocked: ${liveness.failure}"
            admission is IdeaIndexSemanticAdmission.Status.Failed ->
                "IDEA compiler-backed semantic admission failed: ${admission.detail}"
            isDumb -> "IDEA is indexing — analysis results may be incomplete"
            admission is IdeaIndexSemanticAdmission.Status.Pending ->
                "IDEA compiler-backed semantic admission is pending: ${admission.detail}"
            else -> "Kast compiler-backed indexer is ready"
        },
        sourceModuleNames = moduleNames,
        publishedWorkspaceGeneration = null,
        readiness = baseReadiness,
    )
}
