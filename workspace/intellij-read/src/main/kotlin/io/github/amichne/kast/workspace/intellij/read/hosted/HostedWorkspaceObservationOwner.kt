package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceModelIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceNativeReadSettlement
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProjectSession

/** Observational project readiness owned by the existing query service; no semantic admission is delegated here. */
internal class HostedWorkspaceObservationOwner(
    private val project: Project,
    private val owner: Disposable,
    private val hostLifetime: IdeReadHostLifetime,
    private val session: () -> Refinement<AdmittedIdeProjectSession, HostedQueryFailure.Configuration>,
    private val compatibility: () -> Refinement<HostedCompatibility, HostedQueryFailure>,
    private val settlement: () -> WorkspaceNativeReadSettlement,
) {
    private val workspaceReadinessHistory = HostedWorkspaceReadinessHistory()

    /** Fresh passive project admission checks; this does not enter the semantic executor or its budget. */
    fun readiness(root: CanonicalWorkspaceRoot): HostedReadinessDocument {
        when (val state = session()) {
            is Refinement.Rejected -> return state.failure.readinessRejection(HostedQueryStage.REQUEST_ADMISSION)
            is Refinement.Refined -> Unit
        }
        val compatibility =
            when (val admitted = compatibility()) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted.failure.readinessRejection(HostedQueryStage.PROJECT_ADMISSION)
            }
        return observeHostedReadiness {
            io.github.amichne.kast.workspace.intellij.read.ExistingProjectValidation.validate(
                project,
                root,
                compatibility.candidate,
                compatibility.policy,
            )
        }
    }

    /**
     * Fresh project/model preparation observation from this exact endpoint incarnation and retained epoch source. No
     * semantic executor or permit is involved; callers cannot use this detached observation as read authority.
     */
    fun workspaceReadiness(root: CanonicalWorkspaceRoot): WorkspaceCapabilityReadiness =
        observeHostedWorkspaceReadiness(
            WorkspaceModelIdentity(root, hostLifetime),
            workspaceReadinessHistory,
            settlement,
        ) {
            observeWorkspaceModelReadiness(root)
        }

    private fun observeWorkspaceModelReadiness(root: CanonicalWorkspaceRoot): WorkspaceCapabilityReadiness =
        observeHostedWorkspaceModelReadiness(
            project,
            owner,
            WorkspaceModelIdentity(root, hostLifetime),
            session = session,
            compatibility = compatibility,
        )
}
