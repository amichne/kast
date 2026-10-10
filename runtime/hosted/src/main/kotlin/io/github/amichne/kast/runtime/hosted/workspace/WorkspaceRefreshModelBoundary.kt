package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.runtime.hosted.HostedGradleRevision
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryRejectionDocument
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRecovery
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadinessDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/** Native input obligations and import coverage compose with fresh model facts; neither can replace the other. */
internal class WorkspaceRefreshModelBoundary(private val revisions: HostedGradleRevision) {
    fun needsModelReload(): Boolean = revisions.pending()

    fun beginOwnedImport(): HostedGradleRevision.ImportTicket = revisions.beginOwnedImport()

    /** Preserve native rejections; a pending input obligation may only downgrade legacy admission readiness. */
    fun legacyReadiness(current: HostedReadinessDocument): HostedReadinessDocument =
        if (current == HostedReadinessDocument.AdmissionReady && revisions.pending())
            HostedReadinessDocument.Unavailable(
                HostedQueryRejectionDocument(
                    failure = "PROJECT_ADMISSION_REJECTED",
                    detail = Json.encodeToJsonElement(PendingModelInputDetail.GRADLE_MODEL_INCOMPLETE),
                    stage = "PROJECT_ADMISSION",
                    recovery = HostedReadRecovery.GradleModel.Required,
                )
            )
        else current

    fun readiness(current: WorkspaceCapabilityReadiness): WorkspaceCapabilityReadiness =
        if (current is WorkspaceCapabilityReadiness.Ready && revisions.pending())
            WorkspaceCapabilityReadiness.Unavailable(
                current.identity,
                WorkspaceReadinessReason.MODEL_INCOMPLETE,
                WorkspaceReadinessNextAction.REFRESH_MODEL,
                WorkspaceReadinessDetail.PreviouslyObservedModel(
                    current,
                    WorkspaceReadinessDetail.NoAdditionalEvidence,
                ),
            )
        else current
}

@Serializable
private enum class PendingModelInputDetail {
    GRADLE_MODEL_INCOMPLETE
}
