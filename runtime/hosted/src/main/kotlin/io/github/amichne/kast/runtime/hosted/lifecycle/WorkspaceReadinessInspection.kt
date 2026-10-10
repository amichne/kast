package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.WorkspaceInspectionModelIdentity
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochFailure
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionEpochStage
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionNextAction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionObstruction
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadOperation
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionReadinessReason
import io.github.amichne.kast.protocol.contract.WorkspaceInspectionRetainedModel
import io.github.amichne.kast.protocol.contract.WorkspaceReadinessInspectionDocument
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadOperationIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason

/** Pure projection only: it cannot inspect a Project, issue a read capability, or repair native work. */
internal fun WorkspaceCapabilityReadiness.inspectionDocument(): WorkspaceReadinessInspectionDocument =
    when (this) {
        is WorkspaceCapabilityReadiness.Ready -> WorkspaceReadinessInspectionDocument.Ready(identity.document())
        is WorkspaceCapabilityReadiness.Unavailable ->
            WorkspaceReadinessInspectionDocument.Unavailable(identity.document(), obstruction(reason, nextAction, detail))
        is WorkspaceCapabilityReadiness.Pending ->
            WorkspaceReadinessInspectionDocument.Pending(identity.document(), obstruction(reason, nextAction, detail))
        is WorkspaceCapabilityReadiness.Blocked ->
            WorkspaceReadinessInspectionDocument.Blocked(identity.document(), obstruction(reason, nextAction, detail))
    }

private fun obstruction(
    reason: WorkspaceReadinessReason,
    nextAction: WorkspaceReadinessNextAction,
    detail: WorkspaceReadinessDetail,
): WorkspaceInspectionObstruction =
    when (detail) {
        is WorkspaceReadinessDetail.UnsettledReads ->
            WorkspaceInspectionObstruction(reason.document(), nextAction.document(), detail.retainedObservation.retainedDocument(), detail.operations.map { it.document() })
        WorkspaceReadinessDetail.NoAdditionalEvidence -> WorkspaceInspectionObstruction(reason.document(), nextAction.document())
        is WorkspaceReadinessDetail.EpochRejected -> WorkspaceInspectionObstruction(reason.document(), nextAction.document(), epochRejection = detail.failure.document())
    }

private fun WorkspaceModelIdentity.document() = WorkspaceInspectionModelIdentity(root.value, incarnation.value.toString())

private fun WorkspaceCapabilityReadiness.retainedDocument(): WorkspaceInspectionRetainedModel =
    when (this) {
        is WorkspaceCapabilityReadiness.Ready -> WorkspaceInspectionRetainedModel.Observed(identity.document())
        is WorkspaceCapabilityReadiness.Unavailable -> WorkspaceInspectionRetainedModel.Rejected(reason.document(), nextAction.document(), detail.epochDocument())
        is WorkspaceCapabilityReadiness.Pending -> WorkspaceInspectionRetainedModel.Rejected(reason.document(), nextAction.document(), detail.epochDocument())
        is WorkspaceCapabilityReadiness.Blocked -> WorkspaceInspectionRetainedModel.Rejected(reason.document(), nextAction.document(), detail.epochDocument())
    }

private fun WorkspaceReadinessDetail.epochDocument(): WorkspaceInspectionEpochRejection =
    when (this) {
        is WorkspaceReadinessDetail.EpochRejected -> failure.document()
        WorkspaceReadinessDetail.NoAdditionalEvidence,
        is WorkspaceReadinessDetail.UnsettledReads -> WorkspaceInspectionEpochRejection.Unobserved
    }

private fun ProjectReadEpochObservationFailure.document(): WorkspaceInspectionEpochRejection =
    when (this) {
        ProjectReadEpochObservationFailure.WrongThread -> WorkspaceInspectionEpochFailure.WRONG_THREAD.rejection()
        ProjectReadEpochObservationFailure.ProjectDisposed -> WorkspaceInspectionEpochFailure.PROJECT_DISPOSED.rejection()
        ProjectReadEpochObservationFailure.ProjectNotOpen -> WorkspaceInspectionEpochFailure.PROJECT_NOT_OPEN.rejection()
        ProjectReadEpochObservationFailure.ProjectNotInitialized -> WorkspaceInspectionEpochFailure.PROJECT_NOT_INITIALIZED.rejection()
        ProjectReadEpochObservationFailure.ProjectRootUnavailable -> WorkspaceInspectionEpochFailure.PROJECT_ROOT_UNAVAILABLE.rejection()
        ProjectReadEpochObservationFailure.ProjectRootMalformed -> WorkspaceInspectionEpochFailure.PROJECT_ROOT_MALFORMED.rejection()
        ProjectReadEpochObservationFailure.DumbMode -> WorkspaceInspectionEpochFailure.DUMB_MODE.rejection()
        ProjectReadEpochObservationFailure.GradleModelUnavailable -> WorkspaceInspectionEpochFailure.GRADLE_MODEL_UNAVAILABLE.rejection()
        ProjectReadEpochObservationFailure.GradleModelIncomplete -> WorkspaceInspectionEpochFailure.GRADLE_MODEL_INCOMPLETE.rejection()
        ProjectReadEpochObservationFailure.GradleModelAmbiguous -> WorkspaceInspectionEpochFailure.GRADLE_MODEL_AMBIGUOUS.rejection()
        ProjectReadEpochObservationFailure.GradleRootUnavailable -> WorkspaceInspectionEpochFailure.GRADLE_ROOT_UNAVAILABLE.rejection()
        ProjectReadEpochObservationFailure.GradleRootMalformed -> WorkspaceInspectionEpochFailure.GRADLE_ROOT_MALFORMED.rejection()
        ProjectReadEpochObservationFailure.ImportTimestampsIncoherent -> WorkspaceInspectionEpochFailure.IMPORT_TIMESTAMPS_INCOHERENT.rejection()
        ProjectReadEpochObservationFailure.VfsBatchLimitExceeded -> WorkspaceInspectionEpochFailure.VFS_BATCH_LIMIT_EXCEEDED.rejection()
        ProjectReadEpochObservationFailure.VfsPathMalformed -> WorkspaceInspectionEpochFailure.VFS_PATH_MALFORMED.rejection()
        ProjectReadEpochObservationFailure.SignalExhausted -> WorkspaceInspectionEpochFailure.SIGNAL_EXHAUSTED.rejection()
        ProjectReadEpochObservationFailure.ReadPreempted -> WorkspaceInspectionEpochFailure.READ_PREEMPTED.rejection()
        is ProjectReadEpochObservationFailure.ObservationFailed -> WorkspaceInspectionEpochRejection.ObservationFailed(stage.document())
    }

private fun WorkspaceInspectionEpochFailure.rejection() = WorkspaceInspectionEpochRejection.Rejected(this)

private fun ProjectReadEpochObservationStage.document(): WorkspaceInspectionEpochStage = when (this) {
    ProjectReadEpochObservationStage.THREAD -> WorkspaceInspectionEpochStage.THREAD
    ProjectReadEpochObservationStage.DISPOSAL -> WorkspaceInspectionEpochStage.DISPOSAL
    ProjectReadEpochObservationStage.OPEN -> WorkspaceInspectionEpochStage.OPEN
    ProjectReadEpochObservationStage.INITIALIZATION -> WorkspaceInspectionEpochStage.INITIALIZATION
    ProjectReadEpochObservationStage.PROJECT_ROOT -> WorkspaceInspectionEpochStage.PROJECT_ROOT
    ProjectReadEpochObservationStage.PROJECT_MODEL -> WorkspaceInspectionEpochStage.PROJECT_MODEL
    ProjectReadEpochObservationStage.PSI -> WorkspaceInspectionEpochStage.PSI
    ProjectReadEpochObservationStage.VFS -> WorkspaceInspectionEpochStage.VFS
    ProjectReadEpochObservationStage.ROOT_MODEL -> WorkspaceInspectionEpochStage.ROOT_MODEL
    ProjectReadEpochObservationStage.DUMB_MODE -> WorkspaceInspectionEpochStage.DUMB_MODE
}

private fun WorkspaceReadOperationIdentity.document(): WorkspaceInspectionReadOperation =
    when (this) {
        is WorkspaceReadOperationIdentity.Opaque -> WorkspaceInspectionReadOperation.Opaque
        is WorkspaceReadOperationIdentity.Traced -> WorkspaceInspectionReadOperation.Traced(trace.toString())
    }

private fun WorkspaceReadinessReason.document(): WorkspaceInspectionReadinessReason =
    when (this) {
        WorkspaceReadinessReason.PROJECT_DISPOSED -> WorkspaceInspectionReadinessReason.PROJECT_DISPOSED
        WorkspaceReadinessReason.RETIRED_INCARNATION -> WorkspaceInspectionReadinessReason.RETIRED_INCARNATION
        WorkspaceReadinessReason.PROJECT_NOT_OPEN -> WorkspaceInspectionReadinessReason.PROJECT_NOT_OPEN
        WorkspaceReadinessReason.PROJECT_INITIALIZING -> WorkspaceInspectionReadinessReason.PROJECT_INITIALIZING
        WorkspaceReadinessReason.PROJECT_ROOT_UNAVAILABLE -> WorkspaceInspectionReadinessReason.PROJECT_ROOT_UNAVAILABLE
        WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH -> WorkspaceInspectionReadinessReason.PROJECT_IDENTITY_MISMATCH
        WorkspaceReadinessReason.MODEL_UNAVAILABLE -> WorkspaceInspectionReadinessReason.MODEL_UNAVAILABLE
        WorkspaceReadinessReason.MODEL_INCOMPLETE -> WorkspaceInspectionReadinessReason.MODEL_INCOMPLETE
        WorkspaceReadinessReason.INDEXING -> WorkspaceInspectionReadinessReason.INDEXING
        WorkspaceReadinessReason.COMPILER_UNAVAILABLE -> WorkspaceInspectionReadinessReason.COMPILER_UNAVAILABLE
        WorkspaceReadinessReason.HOST_UNAVAILABLE -> WorkspaceInspectionReadinessReason.HOST_UNAVAILABLE
        WorkspaceReadinessReason.HOST_INCOMPATIBLE -> WorkspaceInspectionReadinessReason.HOST_INCOMPATIBLE
        WorkspaceReadinessReason.CONFIGURATION_UNAVAILABLE -> WorkspaceInspectionReadinessReason.CONFIGURATION_UNAVAILABLE
        WorkspaceReadinessReason.OBSERVATION_FAILED -> WorkspaceInspectionReadinessReason.OBSERVATION_FAILED
        WorkspaceReadinessReason.EPOCH_UNAVAILABLE -> WorkspaceInspectionReadinessReason.EPOCH_UNAVAILABLE
        WorkspaceReadinessReason.NATIVE_WORK -> WorkspaceInspectionReadinessReason.NATIVE_WORK
    }

private fun WorkspaceReadinessNextAction.document(): WorkspaceInspectionNextAction =
    when (this) {
        WorkspaceReadinessNextAction.REOPEN_PROJECT -> WorkspaceInspectionNextAction.REOPEN_PROJECT
        WorkspaceReadinessNextAction.ATTACH_HOST -> WorkspaceInspectionNextAction.ATTACH_HOST
        WorkspaceReadinessNextAction.SELECT_PROJECT -> WorkspaceInspectionNextAction.SELECT_PROJECT
        WorkspaceReadinessNextAction.OBSERVE_AGAIN -> WorkspaceInspectionNextAction.OBSERVE_AGAIN
        WorkspaceReadinessNextAction.REFRESH_MODEL -> WorkspaceInspectionNextAction.REFRESH_MODEL
        WorkspaceReadinessNextAction.CHECK_CONFIGURATION -> WorkspaceInspectionNextAction.CHECK_CONFIGURATION
        WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT -> WorkspaceInspectionNextAction.OBSERVE_SETTLEMENT
    }
