package io.github.amichne.kast.idea.backend

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import io.github.amichne.kast.api.contract.RuntimeReadinessLane
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.idea.backend.semantic.toRuntimeStatus
import io.github.amichne.kast.indexstore.snapshot.GraphEvidencePublication

internal suspend fun KastIndexerBackend.runtimeStatusEvidence(): RuntimeStatusResponse {
    val caps = capabilities()
    val isDumb = DumbService.isDumb(project)
    val admission = workspaceSemanticReadAuthority.status()
    val state = when {
        admission is IdeaIndexSemanticAdmission.Status.Failed -> RuntimeState.DEGRADED
        isDumb || admission is IdeaIndexSemanticAdmission.Status.Pending -> RuntimeState.INDEXING
        else -> RuntimeState.READY
    }
    val moduleNames = ModuleManager.getInstance(project).modules.map { it.name }.sorted()
    val modelObservation = IdeaModelReadinessObservation.fromIdeaState(isDumb, moduleNames.size)
    val baseReadiness = kastRuntimeReadiness(KastRuntimeReadinessObservation(admission, modelObservation))
    val readiness = when (
        (admission as? IdeaIndexSemanticAdmission.Status.Ready)?.generation?.graphPublication
    ) {
        is GraphEvidencePublication.Blocked -> baseReadiness.copy(semanticGraph = RuntimeReadinessLane.Blocked)
        else -> baseReadiness
    }
    return RuntimeStatusResponse(
        state = state,
        backendName = caps.backendName,
        backendVersion = caps.backendVersion,
        workspaceRoot = caps.workspaceRoot,
        message = when {
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
        readiness = readiness,
    )
}
