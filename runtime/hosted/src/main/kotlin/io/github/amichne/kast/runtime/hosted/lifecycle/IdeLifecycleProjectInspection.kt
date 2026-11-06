package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.openapi.project.Project
import io.github.amichne.kast.protocol.contract.IdeProjectDescription
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.runtime.hosted.HostedEndpointService
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Inspection defers model reads to the active import owner and never grants readiness from its progress. */
internal fun observeLifecycleProjectReadiness(
    identity: WorkspaceModelIdentity,
    refresh: WorkspaceRefreshInspectionDocument,
    observeNative: () -> WorkspaceCapabilityReadiness,
): WorkspaceCapabilityReadiness =
    if (
        refresh is WorkspaceRefreshInspectionDocument.Running &&
            refresh.active.effect == WorkspaceInspectionRefreshEffect.MODEL_RELOAD
    )
        WorkspaceCapabilityReadiness.Pending(
            identity,
            WorkspaceReadinessReason.NATIVE_WORK,
            WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
        )
    else observeNative()

@Serializable
internal enum class LifecycleProjectInspectionOutcome {
    READY,
    PENDING,
    UNAVAILABLE,
    BLOCKED,
}

@Serializable
internal data class LifecycleProjectInspectionObservation(
    val refresh: WorkspaceRefreshInspectionDocument,
    val outcome: LifecycleProjectInspectionOutcome,
    val elapsedMillis: Long,
)

private val projectInspectionJson = Json { encodeDefaults = true }

private fun recordProjectInspection(
    refresh: WorkspaceRefreshInspectionDocument,
    readiness: WorkspaceCapabilityReadiness,
    started: Long,
) {
    val outcome =
        when (readiness) {
            is WorkspaceCapabilityReadiness.Ready -> LifecycleProjectInspectionOutcome.READY
            is WorkspaceCapabilityReadiness.Pending -> LifecycleProjectInspectionOutcome.PENDING
            is WorkspaceCapabilityReadiness.Unavailable -> LifecycleProjectInspectionOutcome.UNAVAILABLE
            is WorkspaceCapabilityReadiness.Blocked -> LifecycleProjectInspectionOutcome.BLOCKED
        }
    com.intellij.openapi.diagnostic.Logger.getInstance(IdeLifecycleApplication::class.java)
        .info(
            "kast_project_inspection " +
                projectInspectionJson.encodeToString(
                    LifecycleProjectInspectionObservation.serializer(),
                    LifecycleProjectInspectionObservation(
                        refresh,
                        outcome,
                        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0)),
                    ),
                )
        )
}

/** Native facts come only from the selected project owners; this read never starts an endpoint or refresh. */
@Suppress("TooGenericExceptionCaught", "IncorrectCancellationExceptionHandling")
internal fun inspectLifecycleProject(
    project: Project,
    selection: IdeLifecycleInspectionSelection,
): IdeProjectDescription {
    val started = System.nanoTime()
    val identity = WorkspaceModelIdentity(selection.root, IdeReadHostLifetime.fromBoundary(selection.project))
    val endpoint = project.getServiceIfCreated(HostedEndpointService::class.java)
    val refresh =
        try {
            endpoint?.lifecycleRefreshInspection() ?: WorkspaceRefreshInspectionDocument.Unknown
        } catch (_: RuntimeException) {
            WorkspaceRefreshInspectionDocument.Unknown
        }
    val readiness =
        observeLifecycleProjectReadiness(identity, refresh) {
            try {
                val query = project.getService(HostedQueryService::class.java)
                if (query.hostLifetime.value != selection.project)
                    WorkspaceCapabilityReadiness.Blocked(
                        identity,
                        WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH,
                        WorkspaceReadinessNextAction.SELECT_PROJECT,
                    )
                else endpoint?.lifecycleReadiness(selection.root) ?: query.workspaceReadiness(selection.root)
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
        }
    recordProjectInspection(refresh, readiness, started)
    return inspectionProjectDescription(selection.description, readiness, refresh)
}
