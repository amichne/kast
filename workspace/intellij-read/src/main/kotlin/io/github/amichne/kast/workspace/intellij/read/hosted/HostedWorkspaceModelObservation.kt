package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.contract.observeWorkspaceReadSettlement
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProjectSession
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmission
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation

/** The existing query owner supplies native lifetime, retained session and original compatibility proof. */
internal fun observeHostedWorkspaceModelReadiness(
    project: Project,
    owner: Disposable,
    identity: WorkspaceModelIdentity,
    session: () -> Refinement<AdmittedIdeProjectSession, HostedQueryFailure>,
    compatibility: () -> Refinement<HostedCompatibility, HostedQueryFailure>,
): WorkspaceCapabilityReadiness {
    if (Disposer.isDisposed(owner))
        return WorkspaceCapabilityReadiness.Blocked(
            identity,
            WorkspaceReadinessReason.RETIRED_INCARNATION,
            WorkspaceReadinessNextAction.ATTACH_HOST,
        )
    if (project.isDisposed)
        return workspaceReadinessRejected(
            identity,
            ExistingProjectAdmissionFailure.ProjectDisposed,
        )
    val configured =
        when (val state = session()) {
            is Refinement.Refined -> state.value
            is Refinement.Rejected ->
                return WorkspaceCapabilityReadiness.Blocked(
                    identity,
                    WorkspaceReadinessReason.CONFIGURATION_UNAVAILABLE,
                    WorkspaceReadinessNextAction.CHECK_CONFIGURATION,
                )
        }
    val admittedCompatibility =
        when (val current = compatibility()) {
            is Refinement.Refined -> current.value
            is Refinement.Rejected ->
                return WorkspaceCapabilityReadiness.Blocked(
                    identity,
                    WorkspaceReadinessReason.HOST_INCOMPATIBLE,
                    WorkspaceReadinessNextAction.CHECK_CONFIGURATION,
                )
        }
    val validation =
        ExistingProjectValidation.validate(
            project,
            identity.root,
            admittedCompatibility.candidate,
            admittedCompatibility.policy,
        )
    if (validation is ExistingProjectValidation.Rejected)
        return workspaceReadinessRejected(identity, validation.failure)
    return when (
        val admitted =
            configured.admit(project, identity.root, admittedCompatibility.candidate, admittedCompatibility.policy)
    ) {
        is ExistingProjectAdmission.Admitted ->
            observeWorkspaceReadiness(identity, validation, admitted.project::observeReadEpoch)
        is ExistingProjectAdmission.Rejected -> workspaceReadinessRejected(identity, admitted.failure)
    }
}

/** An observation failure preserves history but never grants admission or proves native settlement. */
@Suppress("TooGenericExceptionCaught", "IncorrectCancellationExceptionHandling")
internal fun observeHostedWorkspaceReadiness(
    identity: WorkspaceModelIdentity,
    history: HostedWorkspaceReadinessHistory,
    settlement: () -> WorkspaceNativeReadSettlement,
    observeModel: () -> WorkspaceCapabilityReadiness,
): WorkspaceCapabilityReadiness {
    val observed =
        try {
            observeModel()
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            WorkspaceCapabilityReadiness.Unavailable(
                identity,
                WorkspaceReadinessReason.OBSERVATION_CANCELLED,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
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
    return observeWorkspaceReadSettlement(history.record(observed), settlement())
}
