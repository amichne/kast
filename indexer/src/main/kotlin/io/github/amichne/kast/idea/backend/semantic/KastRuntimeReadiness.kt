package io.github.amichne.kast.idea.backend

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.backend.semantic.CurrentRuntimeBlocker
import io.github.amichne.kast.idea.backend.semantic.CurrentRuntimeLaneState
import io.github.amichne.kast.idea.backend.semantic.toRuntimeStatus
import io.github.amichne.kast.indexstore.snapshot.GraphEvidencePublication
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
    val current: CurrentRuntimeLaneState,
)

/**
 * Proof transition: `KastRuntimeReadinessObservation -> RuntimeReadiness`.
 *
 * Derives all eight capability lanes without making current compiler/model availability depend on
 * persisted source, reference, or graph publication. Raw IntelliJ state is admitted only while
 * constructing [KastRuntimeReadinessObservation].
 */
internal fun kastRuntimeReadiness(
    observation: KastRuntimeReadinessObservation,
): RuntimeReadiness {
    val runtime = when (observation.liveness) {
        RuntimeLivenessAdmission.Live -> currentLaneAvailable(RUNTIME_PROCESS_REVISION)
        is RuntimeLivenessAdmission.Rejected -> currentLaneBlocked(CapabilityLaneBlocker.DEPENDENCY_UNAVAILABLE)
    }
    val model = when {
        observation.liveness is RuntimeLivenessAdmission.Rejected ->
            currentLaneBlocked(CapabilityLaneBlocker.DEPENDENCY_UNAVAILABLE)
        observation.model is IdeaModelReadinessObservation.Indexing ->
            CurrentCapabilityLaneReadiness.Building(
                RuntimeReadinessProgress.derive(
                    stage = RuntimeProgressStage.IDE_INDEXING,
                    work = RuntimeProgressWork.pending(observation.model.discoveredModules),
                    timing = RuntimeProgressTiming.unobserved(),
                ),
            )
        else -> currentLane(observation.current, RuntimeProgressStage.MODEL_SETTLEMENT)
    }
    val workspaceFiles = currentDependentLane(observation, RuntimeProgressStage.MODEL_SETTLEMENT)
    val compiler = currentDependentLane(observation, RuntimeProgressStage.IDE_INDEXING)
    val source = sourceLane(observation.admission)
    val references = referenceLane(observation.admission)
    val graph = graphLane(observation.admission)
    val mutation = when {
        runtime is CurrentCapabilityLaneReadiness.Blocked -> runtime
        model is CurrentCapabilityLaneReadiness.Blocked -> model
        workspaceFiles is CurrentCapabilityLaneReadiness.Building -> workspaceFiles
        compiler is CurrentCapabilityLaneReadiness.Building -> compiler
        observation.admission is IdeaIndexSemanticAdmission.Status.Failed ->
            currentLaneBlocked(CapabilityLaneBlocker.INITIALIZATION_FAILED)
        observation.admission is IdeaIndexSemanticAdmission.Status.Pending ->
            CurrentCapabilityLaneReadiness.Building(
                RuntimeReadinessProgress.uncounted(RuntimeProgressStage.SOURCE_INDEX),
            )
        graph is RetainedCapabilityLaneReadiness.Blocked ->
            currentLaneBlocked(graph.blocker)
        else -> currentLane(observation.current, RuntimeProgressStage.MODEL_SETTLEMENT)
    }
    return RuntimeReadiness(
        runtimeLane = runtime,
        modelLane = model,
        workspaceFilesLane = workspaceFiles,
        compilerLane = compiler,
        sourceIndexLane = source,
        referencesLane = references,
        semanticGraphLane = graph,
        mutationLane = mutation,
    )
}

private fun currentDependentLane(
    observation: KastRuntimeReadinessObservation,
    stage: RuntimeProgressStage,
): CurrentCapabilityLaneReadiness = when {
    observation.liveness is RuntimeLivenessAdmission.Rejected ->
        currentLaneBlocked(CapabilityLaneBlocker.DEPENDENCY_UNAVAILABLE)
    observation.model is IdeaModelReadinessObservation.Indexing ->
        CurrentCapabilityLaneReadiness.Building(RuntimeReadinessProgress.uncounted(RuntimeProgressStage.IDE_INDEXING))
    else -> currentLane(observation.current, stage)
}

private fun currentLane(
    state: CurrentRuntimeLaneState,
    stage: RuntimeProgressStage,
): CurrentCapabilityLaneReadiness = when (state) {
    is CurrentRuntimeLaneState.Available -> currentLaneAvailable(state.epoch.revision.value)
    CurrentRuntimeLaneState.Building ->
        CurrentCapabilityLaneReadiness.Building(RuntimeReadinessProgress.uncounted(stage))
    is CurrentRuntimeLaneState.Blocked -> currentLaneBlocked(
        when (state.blocker) {
            CurrentRuntimeBlocker.PROJECT_DISPOSED -> CapabilityLaneBlocker.DEPENDENCY_UNAVAILABLE
            CurrentRuntimeBlocker.DUMB_MODE -> CapabilityLaneBlocker.INVALIDATED
            CurrentRuntimeBlocker.RUNTIME_FAILED -> CapabilityLaneBlocker.INITIALIZATION_FAILED
        },
    )
}

private fun sourceLane(
    admission: IdeaIndexSemanticAdmission.Status,
): RetainedCapabilityLaneReadiness = retainedLane(
    admission = admission,
    stage = RuntimeProgressStage.SOURCE_INDEX,
    revision = { it.sourceRevision.value },
)

private fun referenceLane(
    admission: IdeaIndexSemanticAdmission.Status,
): RetainedCapabilityLaneReadiness = retainedLane(
    admission = admission,
    stage = RuntimeProgressStage.REFERENCE_INDEX,
    revision = { it.referenceRevision.value },
)

private fun graphLane(
    admission: IdeaIndexSemanticAdmission.Status,
): RetainedCapabilityLaneReadiness = when (admission) {
    is IdeaIndexSemanticAdmission.Status.Ready -> when (val graph = admission.generation.graphPublication) {
        is GraphEvidencePublication.Ready -> retainedLaneAvailable(
            graph.revision.value,
            RetainedCapabilityLaneFreshness.CURRENT,
        )
        is GraphEvidencePublication.Blocked -> retainedLaneBlocked(CapabilityLaneBlocker.INITIALIZATION_FAILED)
    }
    is IdeaIndexSemanticAdmission.Status.Pending -> RetainedCapabilityLaneReadiness.Building(
        progress = RuntimeReadinessProgress.uncounted(RuntimeProgressStage.SEMANTIC_GRAPH),
        fallback = when (val retained = admission.retainedPublication) {
            IdeaIndexSemanticAdmission.RetainedPublication.None -> RetainedCapabilityLaneFallback.None
            is IdeaIndexSemanticAdmission.RetainedPublication.Previous ->
                when (val graph = retained.generation.graphPublication) {
                    is GraphEvidencePublication.Ready -> previousFallback(graph.revision.value)
                    is GraphEvidencePublication.Blocked -> RetainedCapabilityLaneFallback.None
                }
        },
    )
    is IdeaIndexSemanticAdmission.Status.Failed ->
        retainedLaneBlocked(CapabilityLaneBlocker.INITIALIZATION_FAILED)
}

private fun retainedLane(
    admission: IdeaIndexSemanticAdmission.Status,
    stage: RuntimeProgressStage,
    revision: (io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest) -> Long,
): RetainedCapabilityLaneReadiness = when (admission) {
    is IdeaIndexSemanticAdmission.Status.Ready -> retainedLaneAvailable(
        revision(admission.generation),
        RetainedCapabilityLaneFreshness.CURRENT,
    )
    is IdeaIndexSemanticAdmission.Status.Pending -> RetainedCapabilityLaneReadiness.Building(
        progress = RuntimeReadinessProgress.uncounted(stage),
        fallback = when (val retained = admission.retainedPublication) {
            IdeaIndexSemanticAdmission.RetainedPublication.None -> RetainedCapabilityLaneFallback.None
            is IdeaIndexSemanticAdmission.RetainedPublication.Previous -> previousFallback(revision(retained.generation))
        },
    )
    is IdeaIndexSemanticAdmission.Status.Failed ->
        retainedLaneBlocked(CapabilityLaneBlocker.INITIALIZATION_FAILED)
}

/**
 * Proof transition: `Long -> CurrentCapabilityLaneReadiness`.
 *
 * Refines a positive adapter revision into current-only lane evidence. A non-positive revision is
 * retained as the closed `CAPABILITY_UNAVAILABLE` lane blocker. Raw extraction is permitted only
 * where the indexer projects its internal epoch into runtime-status protocol data.
 */
private fun currentLaneAvailable(revision: Long): CurrentCapabilityLaneReadiness = when (
    val parsed = EvidenceRevision.parse(revision)
) {
    is EvidenceRevisionResolution.Resolved -> CurrentCapabilityLaneReadiness.Available(
        CurrentCapabilityLaneEvidence.current(parsed.revision),
    )
    is EvidenceRevisionResolution.Rejected -> currentLaneBlocked(CapabilityLaneBlocker.CAPABILITY_UNAVAILABLE)
}

/**
 * Proof transition: `(Long, RetainedCapabilityLaneFreshness) -> RetainedCapabilityLaneReadiness`.
 *
 * Refines a positive persisted adapter revision and preserves its closed freshness. A non-positive
 * revision becomes `CAPABILITY_UNAVAILABLE`; raw extraction is permitted only at this runtime-status
 * adapter boundary.
 */
private fun retainedLaneAvailable(
    revision: Long,
    freshness: RetainedCapabilityLaneFreshness,
): RetainedCapabilityLaneReadiness = when (val parsed = EvidenceRevision.parse(revision)) {
    is EvidenceRevisionResolution.Resolved -> RetainedCapabilityLaneReadiness.Available(
        when (freshness) {
            RetainedCapabilityLaneFreshness.CURRENT ->
                RetainedCapabilityLaneEvidence.current(parsed.revision)
            RetainedCapabilityLaneFreshness.PREVIOUS ->
                RetainedCapabilityLaneEvidence.previous(parsed.revision)
        },
    )
    is EvidenceRevisionResolution.Rejected -> retainedLaneBlocked(CapabilityLaneBlocker.CAPABILITY_UNAVAILABLE)
}

/**
 * Proof transition: `Long -> RetainedCapabilityLaneFallback`.
 *
 * Refines a positive persisted revision into explicitly previous fallback evidence. Invalid raw
 * revisions produce the closed no-fallback state. Raw extraction is permitted only at this
 * runtime-status adapter boundary.
 */
private fun previousFallback(revision: Long): RetainedCapabilityLaneFallback = when (
    val parsed = EvidenceRevision.parse(revision)
) {
    is EvidenceRevisionResolution.Resolved -> RetainedCapabilityLaneFallback.Previous(
        PreviousCapabilityLaneEvidence.previous(parsed.revision),
    )
    is EvidenceRevisionResolution.Rejected -> RetainedCapabilityLaneFallback.None
}

private fun currentLaneBlocked(blocker: CapabilityLaneBlocker): CurrentCapabilityLaneReadiness.Blocked =
    CurrentCapabilityLaneReadiness.Blocked(blocker)

private fun retainedLaneBlocked(blocker: CapabilityLaneBlocker): RetainedCapabilityLaneReadiness.Blocked =
    RetainedCapabilityLaneReadiness.Blocked(blocker)

internal suspend fun KastIndexerBackend.runtimeStatusEvidence(): RuntimeStatusResponse {
    val caps = capabilities()
    val isDumb = DumbService.isDumb(project)
    val liveness = runtimeLivenessAuthority.admit()
    val admission = workspaceSemanticReadAuthority.status()
    val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
    val modelObservation = IdeaModelReadinessObservation.fromIdeaState(isDumb, moduleNames.size)
    val readiness = kastRuntimeReadiness(
        KastRuntimeReadinessObservation(
            liveness = liveness,
            admission = admission,
            model = modelObservation,
            current = progressiveRuntimeAvailability.observe(),
        ),
    )
    val state = when {
        readiness.runtimeLane is CurrentCapabilityLaneReadiness.Blocked ||
            readiness.modelLane is CurrentCapabilityLaneReadiness.Blocked -> RuntimeState.DEGRADED
        readiness.modelLane is CurrentCapabilityLaneReadiness.Building -> RuntimeState.INDEXING
        else -> RuntimeState.READY
    }
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
        publishedWorkspaceGeneration =
            (admission as? IdeaIndexSemanticAdmission.Status.Ready)?.generation?.toRuntimeStatus(),
        retainedWorkspaceGeneration = when (admission) {
            is IdeaIndexSemanticAdmission.Status.Pending -> when (val retained = admission.retainedPublication) {
                IdeaIndexSemanticAdmission.RetainedPublication.None -> RetainedWorkspaceGenerationStatus.None
                is IdeaIndexSemanticAdmission.RetainedPublication.Previous ->
                    RetainedWorkspaceGenerationStatus.Previous(retained.generation.toRuntimeStatus())
            }
            is IdeaIndexSemanticAdmission.Status.Ready,
            is IdeaIndexSemanticAdmission.Status.Failed,
                -> RetainedWorkspaceGenerationStatus.None
        },
        readiness = readiness,
        referenceCoverageState = when (readiness.referencesLane) {
            is RetainedCapabilityLaneReadiness.Available -> ReferenceCoverageState.COMPLETE
            is RetainedCapabilityLaneReadiness.Building -> ReferenceCoverageState.QUALIFIED
            is RetainedCapabilityLaneReadiness.Blocked -> ReferenceCoverageState.UNAVAILABLE
        },
        referenceCoverageLimitations = when (readiness.referencesLane) {
            is RetainedCapabilityLaneReadiness.Available -> emptyList()
            is RetainedCapabilityLaneReadiness.Building ->
                listOf(ReferenceCoverageLimitation.INDEXING_IN_PROGRESS)
            is RetainedCapabilityLaneReadiness.Blocked ->
                listOf(ReferenceCoverageLimitation.INDEX_NOT_COMMITTED)
        },
    )
}

private const val RUNTIME_PROCESS_REVISION = 1L
