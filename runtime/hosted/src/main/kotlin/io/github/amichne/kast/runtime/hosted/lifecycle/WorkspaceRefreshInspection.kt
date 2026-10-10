package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshAttempt
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshEffect
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshStatus
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiter
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRefreshWaiterIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshInspectionDocument
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshStage as PublicStage
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshAttemptInspection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshFailure
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshInspection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshNativeEffect
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshRejection
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStage
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshStatus
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshWaiterIdentity
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshWaiterInspection

/** A pure snapshot projection, never another refresh owner or inference of native termination. */
internal fun WorkspaceRefreshInspection.inspectionDocument(): WorkspaceRefreshInspectionDocument =
    when (this) {
        WorkspaceRefreshInspection.Idle -> WorkspaceRefreshInspectionDocument.Idle
        is WorkspaceRefreshInspection.Retired ->
            WorkspaceRefreshInspectionDocument.Retired(unsettled.map { it.document() })
        is WorkspaceRefreshInspection.Running ->
            WorkspaceRefreshInspectionDocument.Running(active.document(), queued.map { it.document() })
        is WorkspaceRefreshInspection.AwaitingAdmission ->
            WorkspaceRefreshInspectionDocument.AwaitingAdmission(waiters.map { it.document() })
    }

private fun WorkspaceRefreshAttemptInspection.document() =
    WorkspaceInspectionRefreshAttempt(
        id.value,
        effect.document(),
        stamp.value,
        initiator.document(),
        waiters.map { it.document() },
    )

private fun WorkspaceRefreshWaiterInspection.document() =
    WorkspaceInspectionRefreshWaiter(identity.document(), status.document())

private fun WorkspaceRefreshWaiterIdentity.document(): WorkspaceInspectionRefreshWaiterIdentity =
    when (this) {
        is WorkspaceRefreshWaiterIdentity.Request -> WorkspaceInspectionRefreshWaiterIdentity.Request(id.value)
        WorkspaceRefreshWaiterIdentity.ReadPreparation -> WorkspaceInspectionRefreshWaiterIdentity.ReadPreparation
    }

private fun WorkspaceRefreshNativeEffect.document(): WorkspaceInspectionRefreshEffect =
    when (this) {
        WorkspaceRefreshNativeEffect.INCREMENTAL_FILES -> WorkspaceInspectionRefreshEffect.INCREMENTAL_FILES
        WorkspaceRefreshNativeEffect.FORCED_FILES -> WorkspaceInspectionRefreshEffect.FORCED_FILES
        WorkspaceRefreshNativeEffect.MODEL_RELOAD -> WorkspaceInspectionRefreshEffect.MODEL_RELOAD
    }

private fun WorkspaceRefreshStatus.document(): WorkspaceInspectionRefreshStatus =
    when (this) {
        WorkspaceRefreshStatus.Complete -> WorkspaceInspectionRefreshStatus.Complete
        is WorkspaceRefreshStatus.Pending ->
            WorkspaceInspectionRefreshStatus.Pending(
                when (stage) {
                    WorkspaceRefreshStage.QUEUED -> PublicStage.QUEUED
                    WorkspaceRefreshStage.EFFECT -> PublicStage.EFFECT
                    WorkspaceRefreshStage.ADMISSION -> PublicStage.ADMISSION
                }
            )
        is WorkspaceRefreshStatus.Failed -> WorkspaceInspectionRefreshStatus.Failed(reason.document())
        is WorkspaceRefreshStatus.Rejected -> WorkspaceInspectionRefreshStatus.Rejected(reason.document())
    }

private fun WorkspaceRefreshFailure.document(): WorkspaceInspectionRefreshFailure =
    when (this) {
        WorkspaceRefreshFailure.BUSY -> WorkspaceInspectionRefreshFailure.BUSY
        WorkspaceRefreshFailure.UNSAVED_DOCUMENTS -> WorkspaceInspectionRefreshFailure.UNSAVED_DOCUMENTS
        WorkspaceRefreshFailure.UNLINKED_BUILD -> WorkspaceInspectionRefreshFailure.UNLINKED_BUILD
        WorkspaceRefreshFailure.EFFECT_FAILED -> WorkspaceInspectionRefreshFailure.EFFECT_FAILED
        WorkspaceRefreshFailure.ROOT_UNAVAILABLE -> WorkspaceInspectionRefreshFailure.ROOT_UNAVAILABLE
        WorkspaceRefreshFailure.CANCELLED -> WorkspaceInspectionRefreshFailure.CANCELLED
        WorkspaceRefreshFailure.DISPOSED -> WorkspaceInspectionRefreshFailure.DISPOSED
        WorkspaceRefreshFailure.DEADLINE_EXCEEDED -> WorkspaceInspectionRefreshFailure.DEADLINE_EXCEEDED
    }

private fun WorkspaceRefreshRejection.document(): WorkspaceInspectionRefreshRejection =
    when (this) {
        WorkspaceRefreshRejection.INVALID_REQUEST -> WorkspaceInspectionRefreshRejection.INVALID_REQUEST
        WorkspaceRefreshRejection.REQUEST_CONFLICT -> WorkspaceInspectionRefreshRejection.REQUEST_CONFLICT
        WorkspaceRefreshRejection.CAPACITY -> WorkspaceInspectionRefreshRejection.CAPACITY
        WorkspaceRefreshRejection.UNKNOWN_REQUEST -> WorkspaceInspectionRefreshRejection.UNKNOWN_REQUEST
        WorkspaceRefreshRejection.DISPOSED -> WorkspaceInspectionRefreshRejection.DISPOSED
    }
