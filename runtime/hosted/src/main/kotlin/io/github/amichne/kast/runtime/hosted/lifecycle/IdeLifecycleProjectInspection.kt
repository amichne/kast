package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService

/** Native facts come only from the selected project owners; this read never starts an endpoint or refresh. */
@Suppress("TooGenericExceptionCaught", "IncorrectCancellationExceptionHandling")
internal fun inspectLifecycleProject(
    project: Project,
    selection: IdeLifecycleInspectionSelection,
): IdeProjectDescription {
    val identity = WorkspaceModelIdentity(selection.root, IdeReadHostLifetime.fromBoundary(selection.project))
    val readiness =
        try {
            val query = project.getService(HostedQueryService::class.java)
            if (query.hostLifetime.value != selection.project)
                WorkspaceCapabilityReadiness.Blocked(
                    identity,
                    WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH,
                    WorkspaceReadinessNextAction.SELECT_PROJECT,
                )
            else query.workspaceReadiness(selection.root)
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            WorkspaceCapabilityReadiness.Unavailable(
                identity,
                WorkspaceReadinessReason.OBSERVATION_CANCELLED,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )
        } catch (_: RuntimeException) {
            WorkspaceCapabilityReadiness.Unavailable(
                identity,
                WorkspaceReadinessReason.OBSERVATION_FAILED,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )
        }
    val refresh =
        try {
            project.getServiceIfCreated(HostedEndpointService::class.java)?.lifecycleRefreshInspection()
                ?: WorkspaceRefreshInspectionDocument.Unknown
        } catch (_: RuntimeException) {
            WorkspaceRefreshInspectionDocument.Unknown
        }
    return inspectionProjectDescription(selection.description, readiness, refresh)
}
