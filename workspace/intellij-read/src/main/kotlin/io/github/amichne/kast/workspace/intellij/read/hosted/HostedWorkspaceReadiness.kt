package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation

/** The adapter maps its established project policy; it never substitutes refresh completion for epoch evidence. */
internal fun observeWorkspaceReadiness(
    identity: WorkspaceModelIdentity,
    validation: ExistingProjectValidation,
    observeEpoch: () -> ProjectReadEpochObservation,
): WorkspaceCapabilityReadiness =
    when (validation) {
        is ExistingProjectValidation.Rejected -> workspaceReadinessRejected(identity, validation.failure)
        ExistingProjectValidation.Validated ->
            when (val current = observeEpoch()) {
                is ProjectReadEpochObservation.Observed -> WorkspaceCapabilityReadiness.Ready(identity, current.epoch)
                is ProjectReadEpochObservation.Rejected ->
                    when (current.failure) {
                        ProjectReadEpochObservationFailure.ProjectDisposed ->
                            WorkspaceCapabilityReadiness.Blocked(
                                identity,
                                WorkspaceReadinessReason.PROJECT_DISPOSED,
                                WorkspaceReadinessNextAction.REOPEN_PROJECT,
                                WorkspaceReadinessDetail.EpochRejected(current.failure),
                            )
                        ProjectReadEpochObservationFailure.DumbMode ->
                            WorkspaceCapabilityReadiness.Pending(
                                identity,
                                WorkspaceReadinessReason.INDEXING,
                                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                                WorkspaceReadinessDetail.EpochRejected(current.failure),
                            )
                        ProjectReadEpochObservationFailure.ReadPreempted ->
                            WorkspaceCapabilityReadiness.Pending(
                                identity,
                                WorkspaceReadinessReason.NATIVE_WORK,
                                WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                                WorkspaceReadinessDetail.EpochRejected(current.failure),
                            )
                        else ->
                            WorkspaceCapabilityReadiness.Unavailable(
                                identity,
                                WorkspaceReadinessReason.EPOCH_UNAVAILABLE,
                                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
                                WorkspaceReadinessDetail.EpochRejected(current.failure),
                            )
                    }
            }
    }

internal fun workspaceReadinessRejected(
    identity: WorkspaceModelIdentity,
    failure: ExistingProjectAdmissionFailure,
): WorkspaceCapabilityReadiness {
    val reason =
        when (failure) {
            ExistingProjectAdmissionFailure.ProjectDisposed -> WorkspaceReadinessReason.PROJECT_DISPOSED
            ExistingProjectAdmissionFailure.ProjectNotOpen -> WorkspaceReadinessReason.PROJECT_NOT_OPEN
            ExistingProjectAdmissionFailure.ProjectNotInitialized -> WorkspaceReadinessReason.PROJECT_INITIALIZING
            ExistingProjectAdmissionFailure.ProjectRootUnavailable -> WorkspaceReadinessReason.PROJECT_ROOT_UNAVAILABLE
            ExistingProjectAdmissionFailure.ProjectRootMismatch,
            ExistingProjectAdmissionFailure.RetainedAuthorityMismatch -> WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH
            ExistingProjectAdmissionFailure.GradleModelUnavailable -> WorkspaceReadinessReason.MODEL_UNAVAILABLE
            ExistingProjectAdmissionFailure.GradleModelIncomplete -> WorkspaceReadinessReason.MODEL_INCOMPLETE
            ExistingProjectAdmissionFailure.DumbMode -> WorkspaceReadinessReason.INDEXING
            ExistingProjectAdmissionFailure.K2Unavailable -> WorkspaceReadinessReason.COMPILER_UNAVAILABLE
            ExistingProjectAdmissionFailure.HostIdentityUnavailable -> WorkspaceReadinessReason.HOST_UNAVAILABLE
            is ExistingProjectAdmissionFailure.HostIncompatible -> WorkspaceReadinessReason.HOST_INCOMPATIBLE
            is ExistingProjectAdmissionFailure.ObservationFailed -> WorkspaceReadinessReason.OBSERVATION_FAILED
        }
    return when (reason) {
        WorkspaceReadinessReason.PROJECT_DISPOSED ->
            WorkspaceCapabilityReadiness.Blocked(identity, reason, WorkspaceReadinessNextAction.REOPEN_PROJECT)
        WorkspaceReadinessReason.PROJECT_IDENTITY_MISMATCH,
        WorkspaceReadinessReason.PROJECT_NOT_OPEN ->
            WorkspaceCapabilityReadiness.Blocked(identity, reason, WorkspaceReadinessNextAction.SELECT_PROJECT)
        WorkspaceReadinessReason.INDEXING,
        WorkspaceReadinessReason.PROJECT_INITIALIZING ->
            WorkspaceCapabilityReadiness.Pending(identity, reason, WorkspaceReadinessNextAction.OBSERVE_AGAIN)
        WorkspaceReadinessReason.MODEL_UNAVAILABLE,
        WorkspaceReadinessReason.MODEL_INCOMPLETE ->
            WorkspaceCapabilityReadiness.Unavailable(identity, reason, WorkspaceReadinessNextAction.REFRESH_MODEL)
        WorkspaceReadinessReason.COMPILER_UNAVAILABLE,
        WorkspaceReadinessReason.HOST_UNAVAILABLE,
        WorkspaceReadinessReason.HOST_INCOMPATIBLE ->
            WorkspaceCapabilityReadiness.Blocked(identity, reason, WorkspaceReadinessNextAction.CHECK_CONFIGURATION)
        else -> WorkspaceCapabilityReadiness.Unavailable(identity, reason, WorkspaceReadinessNextAction.OBSERVE_AGAIN)
    }
}
